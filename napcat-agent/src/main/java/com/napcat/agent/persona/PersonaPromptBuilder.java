package com.napcat.agent.persona;

/**
 * 人格 Prompt 构建器。
 */
public class PersonaPromptBuilder {

    /**
     * 将人格的 system prompt 注入到最终的 system prompt 中。
     */
    public String build(Persona persona, String extraContext) {
        StringBuilder sb = new StringBuilder();
        if (persona != null && persona.getSystemPrompt() != null) {
            sb.append(persona.getSystemPrompt());
        }
        if (extraContext != null && !extraContext.isBlank()) {
            sb.append("\n\n").append(extraContext);
        }
        return sb.toString();
    }
}
