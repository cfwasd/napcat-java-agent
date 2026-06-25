package com.dingdong.qqofficial;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * QQ 官方 openid ↔ 内部 long ID 映射。
 */
public class QqOfficialIdMapper {

    private final Map<String, Long> openidToLong = new ConcurrentHashMap<>();
    private final Map<Long, String> longToOpenid = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(10_000_000L);

    public long toUserId(String userOpenid) {
        return openidToLong.computeIfAbsent(userOpenid, key -> {
            long id = Math.abs(key.hashCode());
            while (longToOpenid.containsKey(id) && !key.equals(longToOpenid.get(id))) {
                id = counter.incrementAndGet();
            }
            longToOpenid.put(id, key);
            return id;
        });
    }

    public long toGroupId(String groupOpenid) {
        return toUserId(groupOpenid);
    }

    public String getUserOpenid(long mappedId) {
        return longToOpenid.get(mappedId);
    }
}
