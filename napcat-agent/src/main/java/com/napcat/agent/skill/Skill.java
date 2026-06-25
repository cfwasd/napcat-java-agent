package com.napcat.agent.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Skill 定义（文本类）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill {
    private String name;
    private String title;
    private String description;
    private String content;
    private List<String> triggers;
    private boolean autoInject;
}
