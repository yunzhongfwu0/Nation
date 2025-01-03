package com.nations.core.models;

import com.nations.core.NationsCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class Nation {
    private final NationsCore plugin;
    private final long id;
    private String name;
    private UUID ownerUUID;
    private int level;
    private double balance;
    private Location spawnPoint;
    private String spawnWorldName;
    private double spawnX, spawnY, spawnZ;
    private float spawnYaw, spawnPitch;
    private boolean hasSpawnCoordinates = false;
    private String serverId;
    private int serverPort;
    private boolean isLocalServer;
    private Territory territory;
    private final Map<UUID, NationMember> members = new HashMap<>();
    private final Set<UUID> invites = new HashSet<>();
    private final long createdTime;
    private Set<Building> buildings = new HashSet<>();
    private Map<UUID, Long> memberActivity = new HashMap<>();
    
    public Nation(long id, String name, UUID ownerUUID, int level, double balance,
                 String serverId, int serverPort, boolean isLocalServer) {
        this.plugin = NationsCore.getInstance();
        this.id = id;
        this.name = name;
        this.ownerUUID = ownerUUID;
        this.level = level;
        this.balance = balance;
        this.serverId = serverId;
        this.serverPort = serverPort;
        this.isLocalServer = isLocalServer;
        this.createdTime = System.currentTimeMillis();
    }
    
    public Nation(long id, String name, UUID ownerUUID, int level, double balance,
                 String worldName, double x, double y, double z, float yaw, float pitch,
                 String serverId, int serverPort, boolean isLocalServer) {
        this(id, name, ownerUUID, level, balance, serverId, serverPort, isLocalServer);
        if (isLocalServer && worldName != null) {
            this.spawnPoint = new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch);
        }
    }
    
    public boolean canInviteMore() {
        return true; // 暂时不限制成员数量
    }
    
    public boolean isInvited(UUID playerUuid) {
        return invites.contains(playerUuid);
    }
    
    public void addInvite(UUID playerUuid) {
        invites.add(playerUuid);
    }
    
    public void removeInvite(UUID playerUuid) {
        invites.remove(playerUuid);
    }
    
    public void clearInvites() {
        invites.clear();
    }
    
    public boolean addMember(UUID playerUuid, String rankStr) {
        if (members.containsKey(playerUuid)) return false;
        
        NationRank rank = NationRank.fromString(rankStr);
        if (rank == null) rank = NationRank.MEMBER;
        
        members.put(playerUuid, new NationMember(
            this.id,
            rank,
            new Date()
        ));
        invites.remove(playerUuid);
        return true;
    }
    
    public boolean removeMember(UUID playerUuid) {
        return members.remove(playerUuid) != null;
    }
    
    public boolean promoteMember(UUID playerUuid, NationRank newRank) {
        NationMember member = members.get(playerUuid);
        if (member == null) return false;
        member.setRank(newRank);
        return true;
    }
    
    public NationRank getMemberRank(UUID playerUuid) {
        if (ownerUUID.equals(playerUuid)) {
            return NationRank.OWNER;
        }
        NationMember member = members.get(playerUuid);
        return member != null ? member.getRank() : null;
    }
    
    public boolean hasPermission(UUID playerUuid, String permission) {
        if (playerUuid.equals(ownerUUID)) {
            return true; // 国主拥有所有权限
        }
        NationRank rank = getMemberRank(playerUuid);
        return rank != null && rank.hasPermission(permission);
    }
    
    public long getCreatedTime() {
        return createdTime;
    }
    
    public boolean isMember(UUID playerUuid) {
        return ownerUUID.equals(playerUuid) || members.containsKey(playerUuid);
    }
    
    public boolean isInTerritory(Location location) {
        return territory != null && territory.contains(location);
    }
    
    public int getMaxRadius() {
        // 从配置文件获取最大领土范围
        int maxTerritory = plugin.getConfig().getInt("nations.levels." + level + ".max-territory", 30);
        // 配置中是边长，需要除以2得到半径
        return maxTerritory / 2;
    }
    
    public long getId() {
        return id;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public UUID getOwnerUUID() {
        return ownerUUID;
    }
    
    public void setOwnerUUID(UUID ownerUUID) {
        this.ownerUUID = ownerUUID;
    }
    
    public int getLevel() {
        return level;
    }
    
    public void setLevel(int level) {
        this.level = level;
    }
    
    public double getBalance() {
        return balance;
    }
    
    public void setBalance(double balance) {
        this.balance = balance;
    }
    
    public Location getSpawnPoint() {
        // 如果传送点为空但有世界名称和坐标，尝试重新加载
        if (spawnPoint == null && spawnWorldName != null && hasSpawnCoordinates) {
            spawnPoint = plugin.getWorldManager()
                .createLocation(spawnWorldName, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);
            
            if (spawnPoint != null) {
                plugin.getLogger().info(
                    "已为国家 " + name + " 重新加载传送点: " + 
                    String.format("%.1f, %.1f, %.1f in %s", spawnX, spawnY, spawnZ, spawnWorldName)
                );
            }
        }
        return spawnPoint;
    }
    
    public void setSpawnPoint(Location location) {
        if (location != null) {
            this.spawnWorldName = location.getWorld().getName();
            this.spawnX = location.getX();
            this.spawnY = location.getY();
            this.spawnZ = location.getZ();
            this.spawnYaw = location.getYaw();
            this.spawnPitch = location.getPitch();
            this.hasSpawnCoordinates = true;
            this.spawnPoint = location.clone();
        }
    }
    
    /**
     * 检查传送点是否有效
     */
    public boolean isSpawnPointValid() {
        return plugin.getWorldManager()
            .isLocationValid(getSpawnPoint());
    }
    
    /**
     * 尝试修复无效的传送点
     */
    public boolean fixSpawnPoint() {
        if (!hasSpawnCoordinates || spawnWorldName == null) return false;
        
        Location fixed = plugin.getWorldManager()
            .createLocation(spawnWorldName, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);
        
        if (fixed != null) {
            this.spawnPoint = fixed;
            return true;
        }
        return false;
    }
    
    public Territory getTerritory() {
        return territory;
    }
    
    public void setTerritory(Territory territory) {
        this.territory = territory;
    }
    
    public Map<UUID, NationMember> getMembers() {
        return members;
    }
    
    public boolean isLocalServer() {
        return isLocalServer;
    }
    
    public String getServerId() {
        return serverId;
    }
    
    /**
     * 获取出生点世界名称
     */
    public String getSpawnWorldName() {
        return spawnWorldName;
    }
    
    public void setSpawnWorldName(String worldName) {
        this.spawnWorldName = worldName;
    }
    
    /**
     * 设置传送点坐标（用于世界未加载时）
     */
    public void setSpawnCoordinates(double x, double y, double z, float yaw, float pitch) {
        this.spawnX = x;
        this.spawnY = y;
        this.spawnZ = z;
        this.spawnYaw = yaw;
        this.spawnPitch = pitch;
        this.hasSpawnCoordinates = true;
    }
    
    // 添加这些getter方用于保存数据
    public double getSpawnX() { return spawnX; }
    public double getSpawnY() { return spawnY; }
    public double getSpawnZ() { return spawnZ; }
    public float getSpawnYaw() { return spawnYaw; }
    public float getSpawnPitch() { return spawnPitch; }
    public boolean hasSpawnCoordinates() { return hasSpawnCoordinates; }
    
    public boolean hasBuilding(BuildingType type) {
        return buildings.stream()
            .anyMatch(b -> b.getType() == type);
    }
    
    public Building getBuilding(BuildingType type) {
        return buildings.stream()
            .filter(b -> b.getType() == type)
            .findFirst()
            .orElse(null);
    }
    
    public boolean hasBuildingLevel(BuildingType type, int level) {
        Building building = getBuilding(type);
        return building != null && building.getLevel() >= level;
    }
    
    public void addBuilding(Building building) {
        buildings.add(building);
    }
    
    public void removeBuilding(Building building) {
        buildings.remove(building);
    }
    
    // 获取建���加成
    public double getBuildingBonus(String bonusType) {
        return buildings.stream()
            .mapToDouble(b -> b.getBonuses().getOrDefault(bonusType, 0.0))
            .sum();
    }
    
    public Set<Building> getBuildings() {
        return buildings;
    }
    
    public int getMaxMembers() {
        // 使用 NationsCore.getInstance() 获取插件实例
        NationsCore plugin = NationsCore.getInstance();
        
        // 从基础配置获取等级对应的成员上限
        int baseLimit = plugin.getConfig().getInt("nations.levels." + level + ".max-members", 10);
        
        // 计算建筑加成
        int buildingBonus = (int)getBuildingBonus("max_members");
        return baseLimit + buildingBonus;
    }
    
    public int getCurrentMembers() {
        return members.size() + 1; // +1 是因为包括国主
    }
    
    public boolean withdrawMoney(double amount) {
        if (balance >= amount) {
            balance -= amount;
            return true;
        }
        return false;
    }
    
    public void withdraw(double amount) {
        this.balance -= amount;
    }
    
    public void deposit(double amount) {
        this.balance += amount;
    }

    // 获取在线成员列表
    public List<Player> getOnlineMembers() {
        List<Player> onlineMembers = new ArrayList<>();
        // 添加国家所有者（如果在线）
        Player owner = Bukkit.getPlayer(ownerUUID);
        if (owner != null && owner.isOnline()) {
            onlineMembers.add(owner);
        }
        
        // 添加在线成员
        for (UUID memberId : members.keySet()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {
                onlineMembers.add(member);
            }
        }
        return onlineMembers;
    }

    // 更新成员活跃度
    public void updateMemberActivity(UUID playerId) {
        if (!memberActivity.containsKey(playerId)) {
            memberActivity = new HashMap<>();
        }
        memberActivity.put(playerId, System.currentTimeMillis());
    }

    // 获取成员最后活跃时间
    public long getLastActivity(UUID playerId) {
        return memberActivity.getOrDefault(playerId, 0L);
    }

    // 检查成员是否活跃（7天内登录过）
    public boolean isActiveMember(UUID playerId) {
        long lastActive = getLastActivity(playerId);
        return (System.currentTimeMillis() - lastActive) < (7 * 24 * 60 * 60 * 1000);
    }

    public Set<Building> getBuildingsByType(BuildingType type) {
        return buildings.stream()
            .filter(b -> b.getType() == type)
            .collect(Collectors.toSet());
    }
} 