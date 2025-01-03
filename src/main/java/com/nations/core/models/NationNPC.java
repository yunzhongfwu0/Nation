package com.nations.core.models;

import lombok.Getter;
import lombok.Setter;
import net.citizensnpcs.api.npc.NPC;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;

import com.nations.core.NationsCore;
import com.nations.core.models.Transaction.TransactionType;

import java.util.Map;
import java.util.HashMap;

@Getter
@Setter
public class NationNPC {
    private final long id;
    private final NPCType type;
    private final Building workplace;
    private final NPC citizensNPC;
    
    private int level;
    private int experience;
    private int happiness;
    private int energy;
    private WorkState state;
    private Location workPosition;
    private Location restPosition;
    private Inventory inventory;
    private double baseEfficiency;
    private final Map<String, Double> salaryModifiers = new HashMap<>();

    public NationNPC(long id, NPCType type, Building workplace, NPC citizensNPC) {
        this.id = id;
        this.type = type;
        this.workplace = workplace;
        this.citizensNPC = citizensNPC;
        this.level = 1;
        this.experience = 0;
        this.happiness = 100;
        this.energy = 100;
        this.state = WorkState.RESTING;
        this.baseEfficiency = 1.0;
        this.inventory = Bukkit.createInventory(null, 27, "NPC背包 - " + citizensNPC.getName());
    }

    public void addExperience(int amount) {
        this.experience += amount;
        int requiredExp = getRequiredExperience();
        if (this.experience >= requiredExp) {
            this.level++;
            this.experience -= requiredExp;
            // 升级时恢复全部体力和心情
            this.energy = 100;
            this.happiness = 100;
        }
    }

    public int getRequiredExperience() {
        return 1000 + (level * 500); // 每级需要增加500经验
    }

    public double getEfficiency() {
        // 基础效率
        double efficiency = baseEfficiency;
        
        // 等级加成 (每级+5%效率)
        efficiency += (level - 1) * 0.05;
        
        // 心情影响 (50-100之间线性提升，最多+20%效率)
        if (happiness >= 50) {
            efficiency += ((happiness - 50) / 50.0) * 0.2;
        } else {
            efficiency *= happiness / 50.0; // 心情低于50时降低效率
        }
        
        // 体力影响 (低于50时开始降低效率)
        if (energy < 50) {
            efficiency *= energy / 50.0;
        }
        
        return efficiency;
    }

    public void consumeEnergy(int amount) {
        this.energy = Math.max(0, this.energy - amount);
        if (this.energy < 20) {
            this.happiness = Math.max(0, this.happiness - 5);
        }
    }

    public void rest() {
        // 获取当前世界时间
        long time = getCitizensNPC().getEntity().getWorld().getTime();
        
        // 基础恢复量
        int energyRecovery = 5;  // 基础体力恢复
        int happinessRecovery = 1;  // 基础心情恢复
        
        // 如果是夜晚(12000-24000)，恢复速度翻倍
        if (time >= 12000 && time <= 24000) {
            energyRecovery *= 2;
            happinessRecovery *= 2;
        }
        
        // 更新属性
        this.energy = Math.min(100, this.energy + energyRecovery);
        this.happiness = Math.min(100, this.happiness + happinessRecovery);
        
        // 记录日志
        if ((this.energy / 10) > ((this.energy - energyRecovery) / 10)) {
            NationsCore.getInstance().getLogger().info(
                String.format("NPC %s 正在休息 (体力: %d%%, 心情: %d%%)", 
                    getCitizensNPC().getName(), 
                    this.energy,
                    this.happiness)
            );
        }
    }

    public boolean canWork() {
        return energy > 0 && happiness > 0;
    }

    public boolean paySalary() {
        if (workplace != null && workplace.getNation() != null) {
            double salary = getCurrentSalary();
            if (workplace.getNation().getBalance() >= salary) {
                workplace.getNation().withdraw(salary);
                NationsCore.getInstance().getNationManager().recordTransaction(
                    workplace.getNation(),
                    null,
                    TransactionType.WITHDRAW,
                    salary,
                    "支付NPC工资: " + getCitizensNPC().getName()
                );
                return true;
            }
        }
        return false;
    }
    
    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
    
    public Inventory getInventory() {
        return inventory;
    }
    
    public long getId() { return id; }
    public NPCType getType() { return type; }
    public Building getWorkplace() { return workplace; }
    public NPC getCitizensNPC() { return citizensNPC; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public int getExperience() { return experience; }
    public void setExperience(int exp) { this.experience = exp; }
    public int getHappiness() { return happiness; }
    public void setHappiness(int happiness) { this.happiness = happiness; }
    public int getEnergy() { return energy; }
    public void setEnergy(int energy) { this.energy = energy; }
    public Location getWorkPosition() { return workPosition; }
    public void setWorkPosition(Location pos) { this.workPosition = pos; }
    public Location getRestPosition() { return restPosition; }
    public void setRestPosition(Location pos) { this.restPosition = pos; }
    public WorkState getState() { return state; }
    public void setState(WorkState state) { this.state = state; }

    public int getCurrentSalary() {
        double baseSalary = type.getBaseSalary() * (1 + (level - 1) * 0.1);
        
        // 应用所有修改器
        for (double modifier : salaryModifiers.values()) {
            baseSalary *= (1 + modifier);
        }
        
        return (int)Math.max(1, baseSalary); // 确保工资至少为1
    }

    public void gainExperience(int amount) {
        addExperience(amount);
    }

    /**
     * 获取指定技能的数据
     * @param skill 要获取��技能
     * @return 技能数据，如果技能未解锁或不存在则返回null
     */
    public NPCSkillData getSkillData(NPCSkill skill) {
        Map<NPCSkill, NPCSkillData> skills = NationsCore.getInstance()
            .getNPCSkillManager()
            .getNPCSkills(this.id);
            
        if (skills == null) {
            return null;
        }
        
        return skills.get(skill);
    }

    public void addSalaryModifier(String source, double modifier) {
        salaryModifiers.put(source, modifier);
    }

    public void removeSalaryModifier(String source) {
        salaryModifiers.remove(source);
    }

    public Building getBuilding() {
        return workplace;
    }
}