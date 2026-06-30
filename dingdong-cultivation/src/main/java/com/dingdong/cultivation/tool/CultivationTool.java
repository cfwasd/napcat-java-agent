package com.dingdong.cultivation.tool;

import com.dingdong.core.annotation.Tool;
import com.dingdong.core.annotation.ToolParam;
import com.dingdong.core.scheduler.DbManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

import static com.dingdong.cultivation.CultivationConstants.*;

@Slf4j
@Component
public class CultivationTool {

    private final DbManager dbManager;
    private volatile boolean tableReady;

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final String[][] MOVE_POOL = {
        {"天雷破", "20", "引天雷之力，直劈而下"},
        {"玄冰咒", "18", "凝结玄冰，刺骨寒心"},
        {"烈焰掌", "22", "掌心烈焰，焚尽万物"},
        {"万剑诀", "25", "万剑齐发，无处可逃"},
        {"风刃术", "15", "无形风刃，削铁如泥"},
        {"裂地斩", "24", "一刀裂地，山河震动"},
        {"噬魂诀", "19", "吞噬神魂，直击元神"},
        {"紫电青光", "21", "紫电环绕，青光一闪"},
        {"太虚步", "16", "身形飘忽，出其不意"},
        {"金刚印", "23", "金刚伏魔，一掌定乾坤"},
        {"星辰陨", "28", "引星辰之力，陨落凡尘"},
        {"苍龙吟", "26", "苍龙咆哮，声震九霄"},
    };

    public CultivationTool(DbManager dbManager) {
        this.dbManager = dbManager;
    }

    private void ensureTable() {
        if (tableReady) return;
        synchronized (this) {
            if (tableReady) return;
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(cultivationUsersDdl())) {
                stmt.execute();
                tableReady = true;
            } catch (Exception e) {
                log.error("Failed to create cultivation_users table", e);
            }
        }
    }

    private void ensureSparTable() {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "CREATE TABLE IF NOT EXISTS spar_challenges (" +
                 "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                 "group_id INTEGER NOT NULL DEFAULT 0," +
                 "challenger_id INTEGER NOT NULL," +
                 "challenger_name TEXT DEFAULT ''," +
                 "target_id INTEGER NOT NULL," +
                 "target_name TEXT DEFAULT ''," +
                 "created_at TEXT DEFAULT ''," +
                 "status TEXT DEFAULT 'pending')")) {
            stmt.execute();
        } catch (Exception e) {
            log.error("Failed to create spar_challenges table", e);
        }
        try (Connection conn = dbManager.getConnection();
             PreparedStatement idx = conn.prepareStatement(
                 "CREATE INDEX IF NOT EXISTS idx_spar_target_name ON spar_challenges(group_id, target_name, status)")) {
            idx.execute();
        } catch (Exception e) {
            log.error("Failed to create spar_challenges target_name index", e);
        }
    }

    private String now() {
        return LocalDateTime.now().format(ISO_FMT);
    }

    private CultivationUser loadUser(Connection conn, long userId, long groupId) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM cultivation_users WHERE user_id = ? AND group_id = ?")) {
            stmt.setLong(1, userId);
            stmt.setLong(2, groupId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                CultivationUser u = new CultivationUser();
                u.userId = rs.getLong("user_id");
                u.groupId = rs.getLong("group_id");
                u.userName = rs.getString("user_name");
                u.realm = rs.getString("realm");
                u.subLevel = rs.getInt("sub_level");
                u.cultivation = rs.getInt("cultivation");
                u.rootBone = rs.getInt("root_bone");
                u.luck = rs.getInt("luck");
                u.spirit = rs.getInt("spirit");
                u.spiritStones = rs.getInt("spirit_stones");
                u.reputation = rs.getInt("reputation");
                u.lastCultivateTime = rs.getString("last_cultivate_time");
                u.lastDualCultivateTime = rs.getString("last_dual_cultivate_time");
                u.lastCheckinDate = rs.getString("last_checkin_date");
                u.isInjured = rs.getInt("is_injured") != 0;
                u.injuryUntil = rs.getString("injury_until");
                u.hasReborn = rs.getInt("has_reborn") != 0;
                u.hasTribulationPill = rs.getInt("has_tribulation_pill") != 0;
                u.hasRebirthPill = rs.getInt("has_rebirth_pill") != 0;
                return u;
            }
        }
        return null;
    }

    private void saveUser(Connection conn, CultivationUser u) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO cultivation_users (user_id, group_id, user_name, realm, sub_level, " +
                "cultivation, root_bone, luck, spirit, spirit_stones, reputation, " +
                "last_cultivate_time, last_dual_cultivate_time, last_checkin_date, is_injured, injury_until, " +
                "has_reborn, has_tribulation_pill, has_rebirth_pill, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(user_id, group_id) DO UPDATE SET " +
                "user_name = excluded.user_name, realm = excluded.realm, sub_level = excluded.sub_level, " +
                "cultivation = excluded.cultivation, root_bone = excluded.root_bone, luck = excluded.luck, " +
                "spirit = excluded.spirit, spirit_stones = excluded.spirit_stones, " +
                "reputation = excluded.reputation, last_cultivate_time = excluded.last_cultivate_time, " +
                "last_dual_cultivate_time = excluded.last_dual_cultivate_time, " +
                "last_checkin_date = excluded.last_checkin_date, is_injured = excluded.is_injured, " +
                "injury_until = excluded.injury_until, has_reborn = excluded.has_reborn, " +
                "has_tribulation_pill = excluded.has_tribulation_pill, has_rebirth_pill = excluded.has_rebirth_pill")) {
            stmt.setLong(1, u.userId);
            stmt.setLong(2, u.groupId);
            stmt.setString(3, u.userName != null ? u.userName : "");
            stmt.setString(4, u.realm);
            stmt.setInt(5, u.subLevel);
            stmt.setInt(6, u.cultivation);
            stmt.setInt(7, u.rootBone);
            stmt.setInt(8, u.luck);
            stmt.setInt(9, u.spirit);
            stmt.setInt(10, u.spiritStones);
            stmt.setInt(11, u.reputation);
            stmt.setString(12, u.lastCultivateTime != null ? u.lastCultivateTime : "");
            stmt.setString(13, u.lastDualCultivateTime != null ? u.lastDualCultivateTime : "");
            stmt.setString(14, u.lastCheckinDate != null ? u.lastCheckinDate : "");
            stmt.setInt(15, u.isInjured ? 1 : 0);
            stmt.setString(16, u.injuryUntil != null ? u.injuryUntil : "");
            stmt.setInt(17, u.hasReborn ? 1 : 0);
            stmt.setInt(18, u.hasTribulationPill ? 1 : 0);
            stmt.setInt(19, u.hasRebirthPill ? 1 : 0);
            stmt.setString(20, u.createdAt != null ? u.createdAt : "");
            stmt.executeUpdate();
        }
    }

    private static class CultivationUser {
        long userId, groupId;
        String userName;
        String realm = "mortal";
        int subLevel = 1;
        int cultivation;
        int rootBone = 10;
        int luck = 10;
        int spirit = 10;
        int spiritStones = 100;
        int reputation;
        String lastCultivateTime;
        String lastDualCultivateTime;
        String lastCheckinDate;
        boolean isInjured;
        String injuryUntil;
        boolean hasReborn;
        boolean hasTribulationPill;
        boolean hasRebirthPill;
        String createdAt;
    }

    // ==================== @Tool 方法 ====================

    @Tool(
        name = "start_cultivation",
        description = "开启修仙之路。当用户说\"修仙\"\"开始修仙\"\"我要修仙\"\"成为修仙者\"时使用。随机生成根骨/气运/灵力属性。"
    )
    public String startCultivation(
        @ToolParam(value = "user_id", description = "用户ID", required = true) String userIdStr,
        @ToolParam(value = "group_id", description = "群ID（私聊为0）", required = true) String groupIdStr,
        @ToolParam(value = "user_name", description = "用户昵称", required = false) String userName
    ) {
        long userId, groupId;
        try { userId = Long.parseLong(userIdStr.trim()); } catch (NumberFormatException e) { return "❌ 用户ID格式错误"; }
        try { groupId = Long.parseLong(groupIdStr.trim()); } catch (NumberFormatException e) { return "❌ 群ID格式错误"; }

        ensureTable();
        String uName = (userName != null && !userName.isBlank()) ? userName : String.valueOf(userId);

        try (Connection conn = dbManager.getConnection()) {
            CultivationUser user = loadUser(conn, userId, groupId);
            if (user != null) {
                return "⚡ " + uName + " 你已经是修仙者了！\n当前境界：" + realmDisplayName(user.realm, user.subLevel);
            }

            ThreadLocalRandom rng = ThreadLocalRandom.current();
            int rootBone = rng.nextInt(5, 16);
            int luck = rng.nextInt(5, 16);
            int spirit = rng.nextInt(5, 16);

            CultivationUser newUser = new CultivationUser();
            newUser.userId = userId;
            newUser.groupId = groupId;
            newUser.userName = uName;
            newUser.rootBone = rootBone;
            newUser.luck = luck;
            newUser.spirit = spirit;
            newUser.spiritStones = 100;
            newUser.createdAt = now();
            saveUser(conn, newUser);

            int total = rootBone + luck + spirit;
            String talent;
            if (total >= 40) talent = "🌟 天灵根！万中无一的修炼奇才！";
            else if (total >= 35) talent = "🔥 极品灵根，前途不可限量！";
            else if (total >= 30) talent = "✨ 上品灵根，修仙之路一片光明";
            else if (total >= 25) talent = "👍 中品灵根，中规中矩的修仙者";
            else if (total >= 20) talent = "📘 下品灵根，勤能补拙，加油！";
            else talent = "💪 杂灵根...不过谁说杂灵根不能逆天改命？";

            return "⚡ " + uName + " 踏上了修仙之路！\n\n"
                + "━━━━━━━━━━━━━━\n"
                + "🎭 天赋：" + talent + "\n"
                + "━━━━━━━━━━━━━━\n"
                + "🦴 根骨：" + rootBone + "（修炼效率）\n"
                + "🍀 气运：" + luck + "（渡劫/奇遇）\n"
                + "⚔️ 灵力：" + spirit + "（战斗伤害）\n"
                + "💎 初始灵石：100\n"
                + "━━━━━━━━━━━━━━\n\n"
                + "💡 说\"修炼\"开始获取修为！\n"
                + "💡 说\"修仙菜单\"查看全部功能";
        } catch (Exception e) {
            log.error("start_cultivation failed", e);
            return "❌ 修仙之路开启失败，天道异常...";
        }
    }

    @Tool(
        name = "cultivate",
        description = "主动修炼。当用户说\"修炼\"\"开始修炼\"\"打坐\"\"闭关\"时使用。1小时CD。"
    )
    public String cultivate(
        @ToolParam(value = "user_id", description = "用户ID", required = true) String userIdStr,
        @ToolParam(value = "group_id", description = "群ID（私聊为0）", required = true) String groupIdStr,
        @ToolParam(value = "user_name", description = "用户昵称", required = false) String userName
    ) {
        long userId, groupId;
        try { userId = Long.parseLong(userIdStr.trim()); } catch (NumberFormatException e) { return "❌ 用户ID格式错误"; }
        try { groupId = Long.parseLong(groupIdStr.trim()); } catch (NumberFormatException e) { return "❌ 群ID格式错误"; }

        ensureTable();
        String uName = (userName != null && !userName.isBlank()) ? userName : String.valueOf(userId);

        try (Connection conn = dbManager.getConnection()) {
            CultivationUser user = loadUser(conn, userId, groupId);
            if (user == null) {
                return "🤔 " + uName + " 你还没开启修仙之路！说\"修仙\"开始吧~";
            }

            // 重伤检查
            if (user.isInjured) {
                if (user.injuryUntil != null && !user.injuryUntil.isEmpty()) {
                    LocalDateTime until = LocalDateTime.parse(user.injuryUntil, ISO_FMT);
                    if (LocalDateTime.now().isBefore(until)) {
                        return "💔 " + uName + " 你正处于重伤状态，修炼效率-50%！\n"
                            + "⏰ 重伤持续至：" + until.format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "\n"
                            + "💊 可使用疗伤丹立即恢复：说\"购买 疗伤丹\"";
                    } else {
                        user.isInjured = false;
                        user.injuryUntil = "";
                    }
                }
            }

            // CD检查
            if (user.lastCultivateTime != null && !user.lastCultivateTime.isEmpty()) {
                LocalDateTime lastTime = LocalDateTime.parse(user.lastCultivateTime, ISO_FMT);
                long minutesSince = ChronoUnit.MINUTES.between(lastTime, LocalDateTime.now());
                if (minutesSince < 60) {
                    long remainMin = 60 - minutesSince;
                    return "⏳ " + uName + " 修炼冷却中...还需等待 " + remainMin + " 分钟\n💡 修炼CD为1小时，耐心打坐吧~";
                }
            }

            // 被动收益
            int passiveGain = 0;
            if (user.lastCultivateTime != null && !user.lastCultivateTime.isEmpty()) {
                LocalDateTime lastTime = LocalDateTime.parse(user.lastCultivateTime, ISO_FMT);
                double hoursPassed = ChronoUnit.MINUTES.between(lastTime, LocalDateTime.now()) / 60.0;
                hoursPassed = Math.min(hoursPassed, 8.0);
                passiveGain = (int) (hoursPassed * user.rootBone * realmCoeff(user.realm) * 0.5);
            }

            double coeff = realmCoeff(user.realm);
            int activeGain = (int) (user.rootBone * coeff);

            // 加成
            double bonusMultiplier = 1.0;
            if (user.hasReborn) bonusMultiplier += 0.2;
            if (user.isInjured) bonusMultiplier -= 0.5;
            activeGain = (int) (activeGain * bonusMultiplier);
            passiveGain = (int) (passiveGain * bonusMultiplier);

            // 意外事件
            double accidentChance = 0.10 - (user.luck - 10) * 0.005;
            accidentChance = Math.max(0.02, Math.min(0.15, accidentChance));
            String accidentMsg = "";
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            if (rng.nextDouble() < accidentChance) {
                int roll = rng.nextInt(100);
                if (roll < 5) {
                    user.isInjured = true;
                    user.injuryUntil = LocalDateTime.now().plusHours(1).format(ISO_FMT);
                    user.cultivation = user.cultivation / 2;
                    accidentMsg = "\n\n💀 走火入魔！当前修为减半，修炼效率-50%（持续1小时）！";
                } else if (roll < 8) {
                    activeGain *= 3;
                    accidentMsg = "\n\n🌟 天降祥瑞！修为暴击×3！";
                } else if (roll < 9) {
                    int boostAttr = rng.nextInt(3);
                    String attrName;
                    switch (boostAttr) {
                        case 0: user.rootBone = Math.min(15, user.rootBone + 1); attrName = "根骨"; break;
                        case 1: user.luck = Math.min(15, user.luck + 1); attrName = "气运"; break;
                        default: user.spirit = Math.min(15, user.spirit + 1); attrName = "灵力"; break;
                    }
                    accidentMsg = "\n\n🏛️ 你在修炼中感应到上古遗迹..." + attrName + "永久+1！";
                }
            }

            int totalGain = activeGain + passiveGain;
            user.cultivation += totalGain;
            user.lastCultivateTime = now();
            saveUser(conn, user);

            StringBuilder sb = new StringBuilder();
            sb.append("🧘 ").append(uName).append(" 修炼完毕！\n\n");
            sb.append("━━━━━━━━━━━━━━\n");
            sb.append("📈 主动修炼：+").append(activeGain).append(" 修为\n");
            if (passiveGain > 0) sb.append("⏳ 后台收益：+").append(passiveGain).append(" 修为\n");
            sb.append("💎 累计修为：").append(user.cultivation).append("\n");
            if (bonusMultiplier > 1.0) sb.append("🔥 加成倍率：×").append(String.format("%.1f", bonusMultiplier)).append("\n");
            if (user.isInjured) sb.append("💔 重伤减成：-50%\n");
            sb.append("━━━━━━━━━━━━━━");
            if (!accidentMsg.isEmpty()) sb.append(accidentMsg);

            int realmIdx = getRealmIndex(user.realm);
            int cost = getCultivationCost(realmIdx, user.subLevel);
            if (user.cultivation >= cost && user.subLevel < 4) {
                sb.append("\n\n⚡ 修为已满！说\"突破\"来提升小层吧！");
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("cultivate failed", e);
            return "❌ 修炼失败，灵气紊乱...";
        }
    }

    @Tool(
        name = "breakthrough",
        description = "突破当前小层。当用户说\"突破\"\"提升境界\"\"我要突破\"时使用。消耗指定修为值，100%成功。"
    )
    public String breakthrough(
        @ToolParam(value = "user_id", description = "用户ID", required = true) String userIdStr,
        @ToolParam(value = "group_id", description = "群ID（私聊为0）", required = true) String groupIdStr,
        @ToolParam(value = "user_name", description = "用户昵称", required = false) String userName
    ) {
        long userId, groupId;
        try { userId = Long.parseLong(userIdStr.trim()); } catch (NumberFormatException e) { return "❌ 用户ID格式错误"; }
        try { groupId = Long.parseLong(groupIdStr.trim()); } catch (NumberFormatException e) { return "❌ 群ID格式错误"; }

        ensureTable();
        String uName = (userName != null && !userName.isBlank()) ? userName : String.valueOf(userId);

        try (Connection conn = dbManager.getConnection()) {
            CultivationUser user = loadUser(conn, userId, groupId);
            if (user == null) return "🤔 " + uName + " 你还没开启修仙之路！说\"修仙\"开始吧~";

            int realmIdx = getRealmIndex(user.realm);
            if (realmIdx >= REALMS.length - 1 && user.subLevel >= 4) {
                return "👑 " + uName + " 你已达到最高境界——真仙·圆满！已是仙界至尊！";
            }

            if (user.subLevel >= 4) {
                return "⚡ " + uName + " 已到" + REALMS[realmIdx][1] + "圆满！\n突破至下一大境界需要渡劫！说\"渡劫\"开始天劫试炼！";
            }

            int cost = getCultivationCost(realmIdx, user.subLevel);
            if (user.cultivation < cost) {
                int need = cost - user.cultivation;
                return "📉 " + uName + " 修为不足！\n需要：" + cost + " 修为\n当前：" + user.cultivation + " 修为\n还差：" + need + " 修为\n💡 说\"修炼\"获取修为！";
            }

            user.cultivation -= cost;
            user.subLevel++;
            String oldRealm = realmDisplayName(user.realm, user.subLevel - 1);
            String newRealm = realmDisplayName(user.realm, user.subLevel);
            saveUser(conn, user);

            String msg = "🎉 " + uName + " 突破成功！\n\n" + oldRealm + " → " + newRealm + "\n💎 剩余修为：" + user.cultivation + "\n";
            if (user.subLevel >= 4) {
                msg += "\n⚡ 已达" + REALMS[realmIdx][1] + "圆满！\n💀 突破至下一大境界需要渡劫！说\"渡劫\"开始天劫试炼！";
            } else {
                int nextCost = getCultivationCost(realmIdx, user.subLevel);
                msg += "\n📈 下一层需要：" + nextCost + " 修为";
            }
            return msg;
        } catch (Exception e) {
            log.error("breakthrough failed", e);
            return "❌ 突破失败，灵气暴走...";
        }
    }

    @Tool(
        name = "dujie",
        description = "渡劫突破大境界。当用户说\"渡劫\"\"开始渡劫\"\"我要渡劫\"时使用。多轮天雷判定，连续成功才算通过。"
    )
    public String dujie(
        @ToolParam(value = "user_id", description = "用户ID", required = true) String userIdStr,
        @ToolParam(value = "group_id", description = "群ID（私聊为0）", required = true) String groupIdStr,
        @ToolParam(value = "user_name", description = "用户昵称", required = false) String userName
    ) {
        long userId, groupId;
        try { userId = Long.parseLong(userIdStr.trim()); } catch (NumberFormatException e) { return "❌ 用户ID格式错误"; }
        try { groupId = Long.parseLong(groupIdStr.trim()); } catch (NumberFormatException e) { return "❌ 群ID格式错误"; }

        ensureTable();
        String uName = (userName != null && !userName.isBlank()) ? userName : String.valueOf(userId);

        try (Connection conn = dbManager.getConnection()) {
            CultivationUser user = loadUser(conn, userId, groupId);
            if (user == null) return "🤔 " + uName + " 你还没开启修仙之路！说\"修仙\"开始吧~";

            int realmIdx = getRealmIndex(user.realm);
            if (user.subLevel < 4) {
                return "🤔 " + uName + " 需要先达到" + REALMS[realmIdx][1] + "圆满才能渡劫！";
            }
            if (realmIdx >= REALMS.length - 1) {
                return "👑 " + uName + " 你已是真仙，无需渡劫！";
            }

            int totalRounds = 3 + realmIdx;
            double baseSuccessRate = 0.60 + user.luck * 0.02;
            boolean usedTribulationPill = user.hasTribulationPill;
            if (usedTribulationPill) {
                baseSuccessRate += 0.20;
                user.hasTribulationPill = false;
            }
            baseSuccessRate = Math.min(0.90, baseSuccessRate);

            ThreadLocalRandom rng = ThreadLocalRandom.current();
            StringBuilder sb = new StringBuilder();
            sb.append("⚡ 天劫降临！").append(uName).append(" 正在渡劫...\n\n");
            sb.append("目标：").append(REALMS[realmIdx][1]).append(" → ").append(REALMS[realmIdx + 1][1]).append("\n");
            sb.append("天雷轮数：").append(totalRounds).append(" 轮\n");
            sb.append("每轮成功率：").append(String.format("%.0f", baseSuccessRate * 100)).append("%\n");
            if (usedTribulationPill) sb.append("💊 渡劫丹生效：气运+20%！\n");
            sb.append("━━━━━━━━━━━━━━\n");

            int passedRounds = 0;
            for (int i = 1; i <= totalRounds; i++) {
                boolean success = rng.nextDouble() < baseSuccessRate;
                if (success) {
                    passedRounds++;
                    sb.append("✅ 第").append(i).append("轮：成功通过！");
                    if (i < totalRounds) sb.append("（剩余").append(totalRounds - i).append("轮）");
                    sb.append("\n");
                } else {
                    sb.append("❌ 第").append(i).append("轮：被天雷击中！渡劫失败！\n");
                    break;
                }
            }

            sb.append("━━━━━━━━━━━━━━\n");

            if (passedRounds == totalRounds) {
                String oldRealm = realmDisplayName(user.realm, user.subLevel);
                user.realm = REALMS[realmIdx + 1][0];
                user.subLevel = 1;
                user.cultivation = 0;
                saveUser(conn, user);
                String newRealm = realmDisplayName(user.realm, user.subLevel);
                sb.append("🎉 渡劫成功！\n").append(oldRealm).append(" → ").append(newRealm).append("\n\n💪 恭喜踏入新境界！修仙之路更进一步！");
            } else {
                boolean hasRebirthPill = user.hasRebirthPill;
                user.hasRebirthPill = false;

                if (hasRebirthPill) {
                    int dropLevels = rng.nextInt(1, 3);
                    int newRealmIdx = Math.max(0, realmIdx - dropLevels);
                    user.realm = REALMS[newRealmIdx][0];
                    user.subLevel = Math.max(1, user.subLevel - rng.nextInt(1, 3));
                    user.cultivation = user.cultivation / 2;
                    user.isInjured = true;
                    user.injuryUntil = LocalDateTime.now().plusHours(1).format(ISO_FMT);
                    saveUser(conn, user);
                    sb.append("💊 还魂丹生效！免于转世重修！\n💔 但渡劫失败，重伤掉境...\n当前境界：").append(realmDisplayName(user.realm, user.subLevel)).append("\n💊 重伤持续1小时，可使用疗伤丹恢复");
                } else {
                    boolean reincarnate = rng.nextDouble() < 0.80;
                    if (reincarnate) {
                        int oldRootBone = user.rootBone;
                        int retainedRoot = (int) Math.ceil(user.rootBone * 0.5);
                        user.realm = "mortal";
                        user.subLevel = 1;
                        user.cultivation = 0;
                        user.rootBone = retainedRoot;
                        user.reputation = 0;
                        user.hasReborn = true;
                        saveUser(conn, user);
                        sb.append("💀 身死道消...进入转世重修...\n\n");
                        sb.append("━━━━━━━━━━━━━━\n");
                        sb.append("🔄 修为归零，境界重置为凡人\n");
                        sb.append("🦴 根骨保留50%：").append(oldRootBone).append(" → ").append(retainedRoot).append("\n");
                        sb.append("💎 灵石和宗门关系保留\n");
                        sb.append("🌟 获得「轮回印记」：修炼效率+20%\n");
                        sb.append("━━━━━━━━━━━━━━\n\n💡 天道轮回，重新开始！说\"修仙\"再次踏上修仙之路！");
                    } else {
                        int dropLevels = rng.nextInt(1, 3);
                        int newRealmIdx = Math.max(0, realmIdx - dropLevels);
                        user.realm = REALMS[newRealmIdx][0];
                        user.subLevel = Math.max(1, user.subLevel - rng.nextInt(1, 3));
                        user.cultivation = user.cultivation / 2;
                        user.isInjured = true;
                        user.injuryUntil = LocalDateTime.now().plusHours(1).format(ISO_FMT);
                        saveUser(conn, user);
                        sb.append("💔 渡劫失败，重伤掉境！\n当前境界：").append(realmDisplayName(user.realm, user.subLevel)).append("\n💊 重伤持续1小时，可使用疗伤丹恢复");
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("dujie failed", e);
            return "❌ 渡劫失败，天道紊乱...";
        }
    }

    @Tool(
        name = "cultivation_status",
        description = "查看修仙状态面板。当用户说\"修仙状态\"\"我的修仙\"\"查看修仙\"\"修仙面板\"时使用。"
    )
    public String cultivationStatus(
        @ToolParam(value = "user_id", description = "用户ID", required = true) String userIdStr,
        @ToolParam(value = "group_id", description = "群ID（私聊为0）", required = true) String groupIdStr,
        @ToolParam(value = "user_name", description = "用户昵称", required = false) String userName
    ) {
        long userId, groupId;
        try { userId = Long.parseLong(userIdStr.trim()); } catch (NumberFormatException e) { return "❌ 用户ID格式错误"; }
        try { groupId = Long.parseLong(groupIdStr.trim()); } catch (NumberFormatException e) { return "❌ 群ID格式错误"; }

        ensureTable();
        String uName = (userName != null && !userName.isBlank()) ? userName : String.valueOf(userId);

        try (Connection conn = dbManager.getConnection()) {
            CultivationUser user = loadUser(conn, userId, groupId);
            if (user == null) return "🤔 " + uName + " 你还没开启修仙之路！说\"修仙\"开始吧~";

            // 动态计算被动修为收益（离线/后台收益）
            int passiveGain = 0;
            if (user.lastCultivateTime != null && !user.lastCultivateTime.isEmpty()) {
                LocalDateTime lastTime = LocalDateTime.parse(user.lastCultivateTime, ISO_FMT);
                double hoursPassed = ChronoUnit.MINUTES.between(lastTime, LocalDateTime.now()) / 60.0;
                hoursPassed = Math.min(hoursPassed, 8.0); // 最多8小时收益
                passiveGain = (int) (hoursPassed * user.rootBone * realmCoeff(user.realm) * 0.5);
            }

            // 应用加成并更新
            double bonusMultiplier = 1.0;
            if (user.hasReborn) bonusMultiplier += 0.2;
            if (user.isInjured) bonusMultiplier -= 0.5;
            passiveGain = (int) (passiveGain * bonusMultiplier);

            if (passiveGain > 0) {
                user.cultivation += passiveGain;
                user.lastCultivateTime = now(); // 结算后更新时间戳
                saveUser(conn, user);
            }

            int realmIdx = getRealmIndex(user.realm);
            int nextCost = getCultivationCost(realmIdx, user.subLevel);
            String realmFull = realmDisplayName(user.realm, user.subLevel);

            StringBuilder sb = new StringBuilder();
            sb.append("📋 ").append(uName).append(" 的修仙面板\n\n");
            sb.append("━━━━━━━━━━━━━━\n");
            sb.append("🏅 境界：").append(realmFull).append("\n");
            sb.append("💎 修为：").append(user.cultivation).append(" / ").append(nextCost);
            if (user.subLevel >= 4) sb.append("（满，可渡劫）");
            else if (passiveGain > 0) sb.append("（含离线收益 +").append(passiveGain).append("）");
            sb.append("\n━━━━━━━━━━━━━━\n");
            sb.append("🦴 根骨：").append(user.rootBone).append("（修炼效率）\n");
            sb.append("🍀 气运：").append(user.luck).append("（渡劫/奇遇）\n");
            sb.append("⚔️ 灵力：").append(user.spirit).append("（战斗伤害）\n");
            sb.append("💎 灵石：").append(user.spiritStones).append("\n");
            sb.append("⭐ 声望：").append(user.reputation).append("\n");
            sb.append("━━━━━━━━━━━━━━\n");
            if (user.isInjured) sb.append("💔 状态：重伤中（修炼效率-50%）\n");
            if (user.hasReborn) sb.append("🌟 轮回印记：修炼效率+20%\n");
            if (user.hasTribulationPill) sb.append("💊 持有渡劫丹（下次渡劫气运+20%）\n");
            if (user.hasRebirthPill) sb.append("💊 持有还魂丹（渡劫失败免转世）\n");

            return sb.toString();
        } catch (Exception e) {
            log.error("cultivation_status failed", e);
            return "❌ 查询失败，天道紊乱...";
        }
    }

    @Tool(
        name = "cultivation_ranking",
        description = "查看本群修仙排行榜。当用户说\"修仙排行\"\"修仙排名\"\"修仙排行榜\"\"修仙榜\"时使用。"
    )
    public String cultivationRanking(
        @ToolParam(value = "group_id", description = "群ID", required = true) String groupIdStr
    ) {
        long groupId;
        try { groupId = Long.parseLong(groupIdStr.trim()); } catch (NumberFormatException e) { return "❌ 群ID格式错误"; }

        ensureTable();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT user_name, realm, sub_level, reputation FROM cultivation_users " +
                 "WHERE group_id = ? ORDER BY " +
                 "CASE realm WHEN 'zhenxian' THEN 9 WHEN 'dacheng' THEN 8 WHEN 'dujie' THEN 7 " +
                 "WHEN 'huashen' THEN 6 WHEN 'yuanying' THEN 5 WHEN 'jindan' THEN 4 " +
                 "WHEN 'zhuji' THEN 3 WHEN 'lianqi' THEN 2 ELSE 1 END DESC, " +
                 "sub_level DESC, cultivation DESC LIMIT 10")) {
            stmt.setLong(1, groupId);
            ResultSet rs = stmt.executeQuery();

            StringBuilder sb = new StringBuilder();
            sb.append("🏆 修仙排行榜 TOP 10\n\n");
            int rank = 0;
            boolean hasAny = false;
            while (rs.next()) {
                hasAny = true;
                rank++;
                String name = rs.getString("user_name");
                String realm = rs.getString("realm");
                int subLevel = rs.getInt("sub_level");
                int reputation = rs.getInt("reputation");
                String medal = rank == 1 ? "🥇" : rank == 2 ? "🥈" : rank == 3 ? "🥉" : rank + ".";
                sb.append(medal).append(" ").append(name)
                  .append(" — ").append(realmDisplayName(realm, subLevel))
                  .append(" | ⭐").append(reputation).append("\n");
            }
            if (!hasAny) sb.append("本群还没有修仙者...\n💡 说\"修仙\"成为第一个！");
            return sb.toString();
        } catch (Exception e) {
            log.error("cultivation_ranking failed", e);
            return "❌ 查询失败...";
        }
    }

    @Tool(
        name = "spar_initiate",
        description = "发起切磋挑战。当用户说\"切磋 @某人\"\"挑战 @某人\"\"来打架\"时使用。"
    )
    public String sparInitiate(
        @ToolParam(value = "user_id", description = "发起者用户ID", required = true) String userIdStr,
        @ToolParam(value = "group_id", description = "群ID", required = true) String groupIdStr,
        @ToolParam(value = "user_name", description = "发起者昵称", required = false) String userName,
        @ToolParam(value = "target_id", description = "被挑战者用户ID", required = true) String targetIdStr,
        @ToolParam(value = "target_name", description = "被挑战者昵称", required = false) String targetName
    ) {
        long userId, groupId, targetId;
        try { userId = Long.parseLong(userIdStr.trim()); } catch (NumberFormatException e) { return "❌ 用户ID格式错误"; }
        try { groupId = Long.parseLong(groupIdStr.trim()); } catch (NumberFormatException e) { return "❌ 群ID格式错误"; }
        try { targetId = Long.parseLong(targetIdStr.trim()); } catch (NumberFormatException e) { return "❌ 目标用户ID格式错误"; }

        if (userId == targetId) return "🤔 不能和自己切磋！";

        ensureTable();
        ensureSparTable();
        String uName = (userName != null && !userName.isBlank()) ? userName : String.valueOf(userId);
        String tName = (targetName != null && !targetName.isBlank()) ? targetName : String.valueOf(targetId);

        try (Connection conn = dbManager.getConnection()) {
            CultivationUser challenger = loadUser(conn, userId, groupId);
            CultivationUser target = loadUser(conn, targetId, groupId);
            if (challenger == null) return "🤔 " + uName + " 你还没开启修仙之路！";
            if (target == null) return "🤔 " + tName + " 还没开启修仙之路！";

            try (PreparedStatement checkStmt = conn.prepareStatement(
                    "SELECT id FROM spar_challenges WHERE group_id = ? AND challenger_id = ? AND target_id = ? AND status = 'pending'")) {
                checkStmt.setLong(1, groupId);
                checkStmt.setLong(2, userId);
                checkStmt.setLong(3, targetId);
                if (checkStmt.executeQuery().next()) {
                    return "⚔️ 你已经向 " + tName + " 发起过挑战了！等待对方应战...";
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO spar_challenges (group_id, challenger_id, challenger_name, target_id, target_name, created_at, status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 'pending')")) {
                stmt.setLong(1, groupId);
                stmt.setLong(2, userId);
                stmt.setString(3, uName);
                stmt.setLong(4, targetId);
                stmt.setString(5, tName);
                stmt.setString(6, now());
                stmt.executeUpdate();
            }

            return "⚔️ " + uName + " 向 " + tName + " 发起切磋挑战！\n\n"
                + uName + "：" + realmDisplayName(challenger.realm, challenger.subLevel) + "\n"
                + tName + "：" + realmDisplayName(target.realm, target.subLevel) + "\n\n"
                + "💡 " + tName + " 说\"应战\"来接受挑战！";
        } catch (Exception e) {
            log.error("spar_initiate failed", e);
            return "❌ 发起切磋失败...";
        }
    }

    @Tool(
        name = "spar_accept",
        description = "接受切磋挑战。当用户说\"应战\"\"接受挑战\"\"来吧\"时使用。"
    )
    public String sparAccept(
        @ToolParam(value = "user_id", description = "接受者用户ID", required = true) String userIdStr,
        @ToolParam(value = "group_id", description = "群ID", required = true) String groupIdStr,
        @ToolParam(value = "user_name", description = "接受者昵称", required = false) String userName
    ) {
        long userId, groupId;
        try { userId = Long.parseLong(userIdStr.trim()); } catch (NumberFormatException e) { return "❌ 用户ID格式错误"; }
        try { groupId = Long.parseLong(groupIdStr.trim()); } catch (NumberFormatException e) { return "❌ 群ID格式错误"; }

        ensureTable();
        ensureSparTable();
        String uName = (userName != null && !userName.isBlank()) ? userName : String.valueOf(userId);

        try (Connection conn = dbManager.getConnection()) {
            long challengeId;
            String challengerName;
            long challengerId;

            // 1. 先通过 target_id 查（OneBot 渠道，targetId 是真实用户ID）
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, challenger_id, challenger_name FROM spar_challenges " +
                    "WHERE group_id = ? AND target_id = ? AND status = 'pending' ORDER BY id DESC LIMIT 1")) {
                stmt.setLong(1, groupId);
                stmt.setLong(2, userId);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    challengeId = rs.getLong("id");
                    challengerId = rs.getLong("challenger_id");
                    challengerName = rs.getString("challenger_name");
                } else {
                    // 2. 再通过 target_name 查（QQ 官方渠道后备，target_id 是名字hash）
                    try (PreparedStatement stmt2 = conn.prepareStatement(
                            "SELECT id, challenger_id, challenger_name FROM spar_challenges " +
                            "WHERE group_id = ? AND target_name = ? AND status = 'pending' ORDER BY id DESC LIMIT 1")) {
                        stmt2.setLong(1, groupId);
                        stmt2.setString(2, uName);
                        ResultSet rs2 = stmt2.executeQuery();
                        if (rs2.next()) {
                            challengeId = rs2.getLong("id");
                            challengerId = rs2.getLong("challenger_id");
                            challengerName = rs2.getString("challenger_name");
                        } else {
                            return "🤔 " + uName + " 目前没有待接受的切磋挑战！";
                        }
                    }
                }
            }

            // 更新为 accepted，同时修正 target_id（QQ官方渠道下初始是名字hash）
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE spar_challenges SET status = 'accepted', target_id = ? WHERE id = ?")) {
                stmt.setLong(1, userId);
                stmt.setLong(2, challengeId);
                stmt.executeUpdate();
            }

            CultivationUser p1 = loadUser(conn, challengerId, groupId);
            CultivationUser p2 = loadUser(conn, userId, groupId);
            if (p1 == null || p2 == null) return "❌ 一方修仙数据异常，切磋取消...";

            return executeSpar(p1, p2, challengerName, uName, conn);
        } catch (Exception e) {
            log.error("spar_accept failed", e);
            return "❌ 切磋失败，灵力暴走...";
        }
    }

    private String executeSpar(CultivationUser p1, CultivationUser p2, String name1, String name2, Connection conn) throws Exception {
        int hp1 = 100 + (getRealmIndex(p1.realm) * 4 + p1.subLevel) * 50;
        int hp2 = 100 + (getRealmIndex(p2.realm) * 4 + p2.subLevel) * 50;
        int maxHp1 = hp1, maxHp2 = hp2;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int[][] moves1 = pickMoves(rng);
        int[][] moves2 = pickMoves(rng);

        StringBuilder sb = new StringBuilder();
        sb.append("⚔️ ").append(name1).append(" VS ").append(name2).append(" — 切磋开始！\n\n");
        sb.append("【").append(name1).append("】HP: ").append(hp1).append("/").append(maxHp1)
          .append("   【").append(name2).append("】HP: ").append(hp2).append("/").append(maxHp2).append("\n\n");

        int round = 0, moveIdx1 = 0, moveIdx2 = 0;
        while (hp1 > 0 && hp2 > 0 && round < 20) {
            round++;
            if (moveIdx1 < moves1.length) {
                int[] move = moves1[moveIdx1++];
                String moveName = MOVE_POOL[move[0]][0];
                String moveDesc = MOVE_POOL[move[0]][2];
                int baseDmg = Integer.parseInt(MOVE_POOL[move[0]][1]);
                int damage = (int) ((baseDmg + p1.spirit * 2) * rng.nextDouble(0.8, 1.2));
                hp2 -= damage;
                sb.append("第").append(round).append("回合：\n");
                sb.append("🗡️ ").append(name1).append(" 使出「").append(moveName).append("」— ").append(moveDesc).append("！\n");
                sb.append("   造成 ").append(damage).append(" 点伤害！\n");
                sb.append("   【").append(name2).append("】HP: ").append(Math.max(0, hp2)).append("/").append(maxHp2).append("\n\n");
                if (hp2 <= 0) break;
            }
            if (moveIdx2 < moves2.length) {
                int[] move = moves2[moveIdx2++];
                String moveName = MOVE_POOL[move[0]][0];
                String moveDesc = MOVE_POOL[move[0]][2];
                int baseDmg = Integer.parseInt(MOVE_POOL[move[0]][1]);
                int damage = (int) ((baseDmg + p2.spirit * 2) * rng.nextDouble(0.8, 1.2));
                hp1 -= damage;
                sb.append("⚡ ").append(name2).append(" 使出「").append(moveName).append("」— ").append(moveDesc).append("！\n");
                sb.append("   造成 ").append(damage).append(" 点伤害！\n");
                sb.append("   【").append(name1).append("】HP: ").append(Math.max(0, hp1)).append("/").append(maxHp1).append("\n\n");
            }
        }

        if (hp1 <= 0 && hp2 <= 0) {
            sb.append("🤝 平局！双方同时倒地！\n   ").append(name1).append(" +5声望 | ").append(name2).append(" +5声望\n");
            p1.reputation += 5; p2.reputation += 5;
        } else if (hp2 <= 0) {
            sb.append("🏆 ").append(name1).append(" 获胜！\n   ").append(name1).append(" +10声望 | ").append(name2).append(" +15修为（在战斗中领悟）\n");
            p1.reputation += 10; p2.cultivation += 15;
        } else {
            sb.append("🏆 ").append(name2).append(" 获胜！\n   ").append(name2).append(" +10声望 | ").append(name1).append(" +15修为（在战斗中领悟）\n");
            p2.reputation += 10; p1.cultivation += 15;
        }

        saveUser(conn, p1);
        saveUser(conn, p2);
        return sb.toString();
    }

    private int[][] pickMoves(ThreadLocalRandom rng) {
        int count = MOVE_POOL.length;
        int i1 = rng.nextInt(count);
        int i2, i3;
        do { i2 = rng.nextInt(count); } while (i2 == i1);
        do { i3 = rng.nextInt(count); } while (i3 == i1 || i3 == i2);
        return new int[][]{{i1}, {i2}, {i3}};
    }

    // ==================== 共享工具方法 ====================

    static int getCultivationCost(int realmIdx, int subLevel) {
        int base = Integer.parseInt(REALMS[realmIdx][2]);
        return Math.max(MIN_CULTIVATION_COST, (int) (base * Math.pow(1.5, (realmIdx * 4 + subLevel - 1))));
    }
}
