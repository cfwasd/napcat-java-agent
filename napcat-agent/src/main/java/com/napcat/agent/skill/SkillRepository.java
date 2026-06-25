package com.napcat.agent.skill;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Skill 仓库（缓存 + 热重载）。
 */
public class SkillRepository {

    private final List<Skill> skills = new CopyOnWriteArrayList<>();

    public void reload(List<Skill> newSkills) {
        skills.clear();
        skills.addAll(newSkills);
    }

    public List<Skill> getAll() {
        return Collections.unmodifiableList(skills);
    }

    public Optional<Skill> findByName(String name) {
        return skills.stream().filter(s -> s.getName().equals(name)).findFirst();
    }

    /**
     * 根据消息内容匹配触发的 skill。
     */
    public List<Skill> matchByContent(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<Skill> matched = new ArrayList<>();
        for (Skill skill : skills) {
            if (skill.getTriggers() != null) {
                for (String trigger : skill.getTriggers()) {
                    if (text.contains(trigger)) {
                        matched.add(skill);
                        break;
                    }
                }
            }
        }
        return matched;
    }

    public List<Skill> getAutoInjectSkills() {
        return skills.stream().filter(Skill::isAutoInject).toList();
    }
}
