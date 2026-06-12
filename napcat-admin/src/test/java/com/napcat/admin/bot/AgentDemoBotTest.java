package com.napcat.admin.bot;

import com.napcat.core.api.ApiResponse;
import com.napcat.core.api.NapCatApi;
import com.napcat.core.config.BotProperties;
import com.napcat.core.event.PrivateMessageEvent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentDemoBotTest {

    @Test
    void privateProcessForwardSkipsRawApiWhenNapCatApiUnavailable() {
        AgentDemoBot bot = new AgentDemoBot();
        CountingUnavailableNapCatApi api = new CountingUnavailableNapCatApi();
        ReflectionTestUtils.setField(bot, "napCatApi", api);
        ReflectionTestUtils.setField(bot, "botProperties", new BotProperties());

        PrivateMessageEvent event = new PrivateMessageEvent();
        event.setUserId(12345L);

        ReflectionTestUtils.invokeMethod(bot, "sendProcessForwardPrivate", event, List.of("工具执行过程"));

        assertEquals(0, api.callCount);
    }

    static class CountingUnavailableNapCatApi extends NapCatApi {
        int callCount;

        CountingUnavailableNapCatApi() {
            super(null);
        }

        @Override
        public CompletableFuture<ApiResponse> call(String action, Object... keyValues) {
            callCount++;
            return CompletableFuture.completedFuture(new ApiResponse());
        }
    }
}
