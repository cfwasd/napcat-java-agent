package com.napcat.agent.persona;

import com.dingdong.channel.api.ChannelMessageEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 人格匹配器。
 */
@Slf4j
public class PersonaMatcher {

    private final List<Persona> personas;
    private final Persona defaultPersona;

    public PersonaMatcher(List<Persona> personas, Persona defaultPersona) {
        this.personas = personas;
        this.defaultPersona = defaultPersona;
    }

    /**
     * 根据事件和上下文匹配人格。
     */
    public Persona match(ChannelMessageEvent event, String userPreference) {
        // 1. 用户指定人格
        if (userPreference != null && !userPreference.isBlank()) {
            for (Persona p : personas) {
                if (p.getId().equals(userPreference)) return p;
            }
        }

        // 2. 关键词匹配
        if (event != null && event.getPlainText() != null) {
            String text = event.getPlainText();
            for (Persona p : personas) {
                if (p.getTriggers() != null) {
                    for (String trigger : p.getTriggers()) {
                        if (text.contains(trigger)) return p;
                    }
                }
            }
        }

        // 3. 兜底默认人格
        return defaultPersona;
    }
}
