package com.napcat.agent.skill;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Skill 注入器。
 */
@Slf4j
public class SkillInjector {

    private final SkillRepository repository;

    public SkillInjector(SkillRepository repository) {
        this.repository = repository;
    }

    /**
     * 根据消息内容注入相关 skill。
     */
    public String injectSkills(String systemPrompt, String messageContent) {
        List<Skill> matched = repository.matchByContent(messageContent);
        List<Skill> autoInject = repository.getAutoInjectSkills();

        StringBuilder sb = new StringBuilder(systemPrompt);

        if (!autoInject.isEmpty()) {
            sb.append("\n\n## 可用技能\n");
            for (Skill skill : autoInject) {
                sb.append("\n### ").append(skill.getTitle()).append("\n");
                sb.append(skill.getContent()).append("\n");
            }
        }

        if (!matched.isEmpty()) {
            sb.append("\n\n## 匹配的技能\n");
            for (Skill skill : matched) {
                sb.append("\n### ").append(skill.getTitle()).append("\n");
                sb.append(skill.getContent()).append("\n");
            }
        }

        return sb.toString();
    }
}
