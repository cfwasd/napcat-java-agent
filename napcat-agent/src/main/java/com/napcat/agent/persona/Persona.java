package com.napcat.agent.persona;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 人格定义模型。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Persona {
    private String id;
    private String name;
    private String description;
    private String systemPrompt;
    private String avatar;
    private Map<String, Object> traits;
    private List<String> triggers;
    private String preferredChannel;
    private String voiceProfile;
}
