package com.napcat.starter.wechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WechatIdMapperTest {

    @Test
    void sameWechatIdAlwaysMapsToSamePositiveLong() {
        WechatIdMapper mapper = new WechatIdMapper();

        long first = mapper.toUserId("wxid_abc");
        long second = mapper.toUserId("wxid_abc");

        assertTrue(first > 0);
        assertEquals(first, second);
    }

    @Test
    void userAndGroupNamespacesDoNotCollide() {
        WechatIdMapper mapper = new WechatIdMapper();

        long userId = mapper.toUserId("same");
        long groupId = mapper.toGroupId("same@chatroom");

        assertTrue(userId > 0);
        assertTrue(groupId > 0);
        assertNotEquals(userId, groupId);
    }

    @Test
    void blankGroupIdMapsToPrivateGroupZero() {
        WechatIdMapper mapper = new WechatIdMapper();

        assertEquals(0L, mapper.toGroupId(null));
        assertEquals(0L, mapper.toGroupId(""));
        assertEquals(0L, mapper.toGroupId("   "));
    }
}
