package com.napcat.agent.skill;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Skill 加载器。
 * 从 skills/ 目录加载 Markdown 文件。
 */
@Slf4j
public class SkillLoader {

    private final Path skillsPath;

    public SkillLoader(String skillsPath) {
        this.skillsPath = skillsPath != null && !skillsPath.isBlank()
                ? Paths.get(skillsPath) : Paths.get("skills");
    }

    public List<Skill> loadAll() {
        List<Skill> skills = new ArrayList<>();
        if (!Files.exists(skillsPath)) {
            log.warn("Skills directory not found: {}", skillsPath.toAbsolutePath());
            return skills;
        }

        try (Stream<Path> stream = Files.list(skillsPath)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            Skill skill = parseSkill(p.getFileName().toString(), content);
                            if (skill != null) skills.add(skill);
                        } catch (IOException e) {
                            log.warn("Failed to load skill: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list skills directory", e);
        }

        log.info("Loaded {} skills from {}", skills.size(), skillsPath.toAbsolutePath());
        return skills;
    }

    private Skill parseSkill(String fileName, String content) {
        String name = fileName.replace(".md", "");
        String title = name;
        String description = "";
        List<String> triggers = new ArrayList<>();
        boolean autoInject = false;

        // 解析 frontmatter
        if (content.startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end > 0) {
                String frontmatter = content.substring(3, end).trim();
                for (String line : frontmatter.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("title:")) title = line.substring(6).trim();
                    else if (line.startsWith("description:")) description = line.substring(12).trim();
                    else if (line.startsWith("auto_inject:")) autoInject = Boolean.parseBoolean(line.substring(12).trim());
                    else if (line.startsWith("triggers:")) {
                        String triggersStr = line.substring(9).trim();
                        if (triggersStr.startsWith("[") && triggersStr.endsWith("]")) {
                            triggersStr = triggersStr.substring(1, triggersStr.length() - 1);
                            for (String t : triggersStr.split(",")) {
                                String trimmed = t.trim().replaceAll("[\"']", "");
                                if (!trimmed.isEmpty()) triggers.add(trimmed);
                            }
                        }
                    }
                }
                content = content.substring(end + 3).trim();
            }
        }

        return Skill.builder()
                .name(name)
                .title(title)
                .description(description)
                .content(content)
                .triggers(triggers)
                .autoInject(autoInject)
                .build();
    }
}
