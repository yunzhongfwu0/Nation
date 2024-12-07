package com.nations.core.managers;

import com.nations.core.NationsCore;
import com.nations.core.models.Building;
import com.nations.core.models.BuildingFunction;
import com.nations.core.models.BuildingType;
import com.nations.core.models.Nation;
import com.nations.core.utils.ItemNameUtil;
import com.nations.core.utils.MessageUtil;
import com.nations.core.utils.HologramUtil;
import com.nations.core.utils.BuildingBorderUtil;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.World;
import org.bukkit.Particle;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

public class BuildingManager {
    private final NationsCore plugin;
    private final Map<Long, Building> buildings = new HashMap<>();
    private final Map<Long, Set<Building>> buildingsByNation = new HashMap<>();
    
    public BuildingManager(NationsCore plugin) {
        this.plugin = plugin;
        loadBuildings();
    }
    
    private void loadBuildings() {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            buildings.clear();
            buildingsByNation.clear();
            
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM " + plugin.getDatabaseManager().getTablePrefix() + "buildings"
            );
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                Building building = loadBuilding(rs);
                if (building != null) {
                    buildings.put(building.getId(), building);
                    buildingsByNation.computeIfAbsent(building.getNationId(), k -> new HashSet<>())
                        .add(building);
                    
                    // 初始化建筑功能
                    new BuildingFunction(building).runTasks();
                }
            }
            
            plugin.getLogger().info("已加载 " + buildings.size() + " 个建筑");
            
        } catch (SQLException e) {
            plugin.getLogger().severe("加载建筑数据失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private boolean checkRequirements(Nation nation, BuildingType type, Player player) {
        // 检查国家等级
        if (nation.getLevel() < type.getMinNationLevel()) {
            player.sendMessage(MessageUtil.error("建造失败！国家等级不足："));
            player.sendMessage(MessageUtil.error("- 需要等级: " + type.getMinNationLevel()));
            player.sendMessage(MessageUtil.error("- 当前等级: " + nation.getLevel()));
            return false;
        }
        
        // 检查前置建筑
        if (type.getRequiredBuilding() != null) {
            Building required = nation.getBuilding(type.getRequiredBuilding());
            if (required == null || required.getLevel() < type.getRequiredBuildingLevel()) {
                player.sendMessage(MessageUtil.error("建造失败！缺少前置建筑："));
                player.sendMessage(MessageUtil.error("- 需要建筑: " + type.getRequiredBuilding().getDisplayName() + 
                    " Lv." + type.getRequiredBuildingLevel()));
                if (required != null) {
                    player.sendMessage(MessageUtil.error("- 当前等级: Lv." + required.getLevel()));
                }
                return false;
            }
        }
        
        return true;
    }
    
    private boolean checkResources(Nation nation, BuildingType type, Player player) {
        Map<Material, Integer> costs = type.getBuildCosts();
        boolean hasAll = true;
        StringBuilder message = new StringBuilder("§c建造失败！资源不足：\n");
        
        for (Map.Entry<Material, Integer> cost : costs.entrySet()) {
            Material material = cost.getKey();
            int required = cost.getValue();
            int has = countPlayerItems(player, material);
            
            if (has < required) {
                hasAll = false;
                message.append(MessageUtil.formatResourceRequirement(material, required, has)).append("\n");
            }
        }
        
        if (!hasAll) {
            player.sendMessage(message.toString());
            return false;
        }
        return true;
    }
    
    private void deductResources(Nation nation, BuildingType type) {
        Player owner = plugin.getServer().getPlayer(nation.getOwnerUUID());
        if (owner == null) return;
        
        for (Map.Entry<Material, Integer> cost : type.getBuildCosts().entrySet()) {
            removeItems(owner, cost.getKey(), cost.getValue());
        }
    }
    
    private void placeFoundation(Building building) {
        Location loc = building.getBaseLocation();
        
        // 放置建筑结构
        building.getType().placeStructure(loc);
        
        // 添加粒子效果
        new BukkitRunnable() {
            double angle = 0;
            int ticks = 0;
            
            @Override
            public void run() {
                if (ticks++ >= 40) {
                    cancel();
                    return;
                }
                
                angle += Math.PI / 8;
                double radius = building.getSize() / 2.0;
                
                for (double i = 0; i < Math.PI * 2; i += Math.PI / 16) {
                    double x = Math.cos(i + angle) * radius;
                    double z = Math.sin(i + angle) * radius;
                    Location particleLoc = loc.clone().add(x, 0.5, z);
                    loc.getWorld().spawnParticle(Particle.END_ROD, particleLoc, 1, 0, 0, 0, 0);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
    
    private int calculateSize(BuildingType type, int level) {
        return type.getBaseSize() + (level - 1);
    }
    
    private int countPlayerItems(Player player, Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }
    
    private void removeItems(Player player, Material material, int amount) {
        ItemStack[] contents = player.getInventory().getContents();
        int remaining = amount;
        
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() == material) {
                if (item.getAmount() <= remaining) {
                    remaining -= item.getAmount();
                    contents[i] = null;
                } else {
                    item.setAmount(item.getAmount() - remaining);
                    remaining = 0;
                }
            }
        }
        
        player.getInventory().setContents(contents);
    }
    
    private boolean checkBuildingLocation(Nation nation, BuildingType type, Location location) {
        // 检查是否在国家领土内
        if (nation.getTerritory() == null || !nation.getTerritory().contains(location)) {
            return false;
        }
        
        // 计算新建筑的占地范围
        int size = type.getBaseSize();
        int halfSize = size / 2;
        Location baseLocation = location.clone();
        
        // 检查是否与其他建筑重叠
        for (Building existingBuilding : nation.getBuildings()) {
            Location existingLoc = existingBuilding.getBaseLocation();
            int existingSize = existingBuilding.getSize();
            int existingHalfSize = existingSize / 2;
            
            // 检查两个建筑的边界是否重叠
            if (Math.abs(baseLocation.getBlockX() - existingLoc.getBlockX()) <= (halfSize + existingHalfSize + 2) && 
                Math.abs(baseLocation.getBlockZ() - existingLoc.getBlockZ()) <= (halfSize + existingHalfSize + 2)) {
                return false;
            }
        }
        
        return true;
    }
    
    public boolean createBuilding(Nation nation, BuildingType type, Location location) {
        Player owner = plugin.getServer().getPlayer(nation.getOwnerUUID());
        if (owner == null) return false;
        
        // 检查前置条件
        if (!checkRequirements(nation, type, owner)) {
            return false;
        }
        
        // 检查资源
        if (!checkResources(nation, type, owner)) {
            return false;
        }
        
        // 检查建筑位置
        if (!checkBuildingLocation(nation, type, location)) {
            owner.sendMessage(MessageUtil.error("建造失败！位置不合适："));
            if (nation.getTerritory() == null) {
                owner.sendMessage(MessageUtil.error("- 国家还未设置领土"));
            } else if (!nation.getTerritory().contains(location)) {
                owner.sendMessage(MessageUtil.error("- 必须在国家领土范围内建造"));
            } else {
                owner.sendMessage(MessageUtil.error("- 与其他建筑太近，需要保持至少2格间距"));
            }
            return false;
        }
        
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 扣除资源
                deductResources(nation, type);
                
                // 插入建筑记录
                PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO " + plugin.getDatabaseManager().getTablePrefix() + 
                    "buildings (nation_id, type, level, world, x, y, z, created_time) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
                );
                
                stmt.setLong(1, nation.getId());
                stmt.setString(2, type.name());
                stmt.setInt(3, 1);
                stmt.setString(4, location.getWorld().getName());
                stmt.setDouble(5, location.getX());
                stmt.setDouble(6, location.getY());
                stmt.setDouble(7, location.getZ());
                stmt.setLong(8, System.currentTimeMillis() / 1000); // Unix时间戳(秒)
                
                stmt.executeUpdate();
                
                // 获取生成的ID
                ResultSet rs = stmt.getGeneratedKeys();
                if (rs.next()) {
                    long buildingId = rs.getLong(1);
                    
                    // 创建建筑实例
                    Building building = new Building(
                        buildingId,
                        nation.getId(),
                        type,
                        1,
                        location,
                        calculateSize(type, 1)
                    );
                    
                    // 添加到缓存
                    buildings.put(buildingId, building);
                    buildingsByNation.computeIfAbsent(nation.getId(), k -> new HashSet<>())
                        .add(building);
                    nation.addBuilding(building);
                    
                    // 放置建筑结构
                    placeFoundation(building);
                    
                    // 创建全息显示
                    HologramUtil.createBuildingHologram(building);

                    // 初始化建筑功能
                    new BuildingFunction(building).runTasks();
                    // 显示建筑边界
                    BuildingBorderUtil.showBuildingBorder(building);

                    // 发送成功消息
                    owner.sendMessage(MessageUtil.success("建造成功！"));
                    
                    conn.commit();
                    return true;
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("创建建筑失败: " + e.getMessage());
            e.printStackTrace();
            owner.sendMessage(MessageUtil.error("建造失败！请联系管理员检查后台错误。"));
        }
        
        return false;
    }
    
    public void reloadBuildings() {
        // 停止所有现有的建功能任务
        for (Building building : buildings.values()) {
            if (building != null) {
                // 取消旧的任务
                plugin.getServer().getScheduler().cancelTasks(plugin);
            }
        }
        
        // 重新加载建筑
        loadBuildings();
    }
    
    public boolean demolishBuilding(Nation nation, Building building) {
        // 检查权限
        if (!building.getNation().equals(nation)) {
            return false;
        }

        try {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // 获取建筑位置的世界
                    Location location = building.getBaseLocation();
                    World world = location != null ? location.getWorld() : null;
                    
                    // 只有在世界有效时才执行清理操作
                    if (world != null) {
                        // 移除全息显示
                        HologramUtil.removeBuildingHologram(location);
                        
                        // 移除边界显示
                        BuildingBorderUtil.removeBuildingBorder(building);
                        
                        // 移除相关NPC
                        plugin.getNPCManager().removeAllBuildingNPCs(building);
                        
                        // 清除建筑结构
                        int halfSize = building.getSize() / 2;
                        for (int x = -halfSize; x <= halfSize; x++) {
                            for (int z = -halfSize; z <= halfSize; z++) {
                                for (int y = 0; y < 10; y++) {
                                    location.clone().add(x, y, z).getBlock().setType(Material.AIR);
                                }
                            }
                        }
                        
                        // 返还部分资源
                        Map<Material, Integer> costs = building.getType().getBuildCosts();
                        costs.forEach((material, amount) -> {
                            int refund = (int)(amount * 0.5);
                            ItemStack refundItem = new ItemStack(material, refund);
                            world.dropItemNaturally(location, refundItem);
                        });
                    }
                    
                    // 从数据库中删除建筑记录
                    PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM " + plugin.getDatabaseManager().getTablePrefix() + 
                        "buildings WHERE id = ?"
                    );
                    stmt.setLong(1, building.getId());
                    stmt.executeUpdate();
                    
                    // 从国家中移除建筑
                    nation.removeBuilding(building);
                    
                    // 从缓存中移除
                    buildings.remove(building.getId());
                    
                    conn.commit();
                    return true;
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("拆除建筑失败: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("拆除建筑失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    public void saveBuilding(Building building) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "UPDATE " + plugin.getDatabaseManager().getTablePrefix() + 
                "buildings SET level = ? WHERE id = ?"
            );
            stmt.setInt(1, building.getLevel());
            stmt.setLong(2, building.getId());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("保存建筑失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public Building getBuildingById(long id) {
        return buildings.get(id);
    }
    
    private Building loadBuilding(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        long nationId = rs.getLong("nation_id");
        BuildingType type = BuildingType.valueOf(rs.getString("type"));
        int level = rs.getInt("level");
        
        // 先获取国家
        Nation nation = plugin.getNationManager().getNationById(nationId);
        if (nation == null) {
            plugin.getLogger().warning("找不到ID为 " + nationId + " 的国家，跳过加载建筑 " + id);
            return null;
        }
        
        // 获取位置
        String worldName = rs.getString("world");
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("找不到世界 " + worldName + "，跳过加载建筑 " + id);
            return null;
        }
        
        Location location = new Location(
            world,
            rs.getDouble("x"),
            rs.getDouble("y"),
            rs.getDouble("z")
        );
        
        // 创建建筑实例
        Building building = new Building(
            id,
            nationId,
            type,
            level,
            location,
            calculateSize(type, level)
        );
        
        // 添加到国家
        nation.addBuilding(building);
        
        plugin.getLogger().info(String.format(
            "已加载建筑: %s (ID: %d, 国家: %s)",
            type.getDisplayName(),
            id,
            nation.getName()
        ));
        
        return building;
    }
    
    public BuildingFunction getBuildingFunction(Building building) {
        return new BuildingFunction(building);
    }
    
    // 其他方法...
}