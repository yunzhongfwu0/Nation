package com.nations.core.models;
import java.util.HashMap;
import java.util.Map;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.bukkit.Location;
import org.bukkit.World;

import com.nations.core.NationsCore;

import org.bukkit.Bukkit;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Building {
    private final long id;
    private final long nationId;
    private final BuildingType type;
    private int level;
    private Location baseLocation;
    private String worldName;
    private int x, y, z;
    private int size;
    private Map<String, Double> bonuses;
    private final Nation nation;
    private final Map<String, Double> efficiencyBonuses = new HashMap<>();
    private String cropType;
    
    public Building(long id, long nationId, BuildingType type, int level, 
                   Location location, int size) {
        this.id = id;
        this.nationId = nationId;
        this.type = type;
        this.level = level;
        this.size = size;
        this.nation = NationsCore.getInstance().getNationManager().getNationById(nationId);
        
        // 保存世界名称和坐标
        if (location != null) {
            this.worldName = location.getWorld() != null ? location.getWorld().getName() : null;
            this.x = location.getBlockX();
            this.y = location.getBlockY();
            this.z = location.getBlockZ();
            this.baseLocation = location;
        }
        
        initializeBonuses();
    }

    public Location getBaseLocation() {
        if (baseLocation == null && worldName != null) {
            baseLocation = NationsCore.getInstance().getWorldManager()
                .createLocation(worldName, x, y, z);
            
            if (baseLocation != null) {
                NationsCore.getInstance().getLogger().info(
                    "已为建筑 " + type.getDisplayName() + " 重新加载位置: " + 
                    String.format("%.1f, %.1f, %.1f in %s", x, y, z, worldName)
                );
            }
        }
        return baseLocation;
    }

    // Getters for coordinates and world name
    public String getWorldName() {
        return worldName;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    private void initializeBonuses() {
        this.bonuses = calculateBonuses();
    }

    private Map<String, Double> calculateBonuses() {
        Map<String, Double> result = new HashMap<>();
        switch (type) {
            case TOWN_HALL:
                result.put("tax_rate", 0.05 * level);
                result.put("max_members", 5.0 * level);
                break;
            case BARRACKS:
                result.put("strength", 1.0 * level);
                result.put("defense", 0.5 * level);
                break;
            case MARKET:
                result.put("trade_discount", 0.02 * level);
                result.put("income_bonus", 0.1 * level);
                break;
            case WAREHOUSE:
                result.put("storage_size", 27.0 * level);
                break;
            case FARM:
                result.put("food_production", 10.0 * level);
                break;
        }
        return result;
    }

    /**
     * 检查建筑是否有效（未被拆除）
     * @return 如果建筑仍然存在于数据库中返回true
     */
    public boolean isValid() {
        // 通过检查建筑是否还存在于数据库中来判断
        try (Connection conn = NationsCore.getInstance().getDatabaseManager().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM " + NationsCore.getInstance().getDatabaseManager().getTablePrefix() + 
                "buildings WHERE id = ?"
            );
            stmt.setLong(1, id);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            NationsCore.getInstance().getLogger().warning("检查建筑有效性失败: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * 检查建筑是否有效（不查询数据库）
     * @return 如果建筑基本信息完整返回true
     */
    public boolean isValidBasic() {
        return baseLocation != null && 
               baseLocation.getWorld() != null && 
               level > 0 && 
               size > 0;
    }

    public BuildingType getType() {
        return type;
    }

    public int getLevel() {
        return level;
    }

    public long getId() {
        return id;
    }

    public Nation getNation() {
        return nation;
    }

    public Map<String, Double> getBonuses() {
        return bonuses;
    }

    public int getSize() {
        return size;
    }

    public void setLevel(int level) { this.level = level; }
    public long getNationId() { return nation.getId(); }

    public void addEfficiencyBonus(String source, double bonus) {
        efficiencyBonuses.put(source, bonus);
    }

    public void removeEfficiencyBonus(String source) {
        efficiencyBonuses.remove(source);
    }

    public double getTotalEfficiencyBonus() {
        return 1.0 + efficiencyBonuses.values().stream()
            .mapToDouble(Double::doubleValue)
            .sum();
    }

    public String getCropType() {
        return cropType;
    }

    public void setCropType(String cropType) {
        this.cropType = cropType;
    }

    public String getCropTypeDisplayName() {
        if (cropType == null) return "无";
        switch (cropType) {
            case "WHEAT": return "小麦";
            case "CARROT": return "胡萝卜";
            case "POTATO": return "马铃薯";
            case "BEETROOT": return "甜菜根";
            case "PUMPKIN": return "南瓜";
            case "MELON": return "西瓜";
            default: return cropType;
        }
    }
} 