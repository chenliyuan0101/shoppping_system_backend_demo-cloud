package com.mall.demo.support;

import com.mall.demo.common.BusinessException;
import com.mall.demo.common.CacheService;
import com.mall.demo.common.MemberStatusCache;
import com.mall.demo.common.PageResult;
import com.mall.demo.common.TokenVersionService;
import com.mall.demo.common.constant.EnableStatus;
import com.mall.demo.common.contract.AddressQueryService;
import com.mall.demo.common.contract.CartCheckoutService;
import com.mall.demo.common.contract.MemberAdminService;
import com.mall.demo.common.contract.MemberQueryService;
import com.mall.demo.common.dto.AddressSnapshotVO;
import com.mall.demo.common.dto.CartClaimResultVO;
import com.mall.demo.common.dto.CartItemSnapshotVO;
import com.mall.demo.common.dto.MemberBriefVO;
import com.mall.demo.common.dto.MemberSnapshotVO;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.type.TypeReference;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>测试期的"用户中心替身"</b>（P3-4）：把单体那 4 个会员域契约实现成
 * "直接读 {@code mall_user} 库"的 JDBC 版本。
 *
 * <h2>为什么必须存在（这是刻意的取舍，不是偷懒）</h2>
 * P3-4 把会员/地址/购物车/收藏/足迹的表与本地实现从单体删干净之后，生产环境里这 4 个契约的<b>唯一</b>实现是
 * {@code app.UserCenterRemoteConfig}（HTTP → {@code mall-user-center}）。
 * 但真库测试跑的是 MockMvc（单进程、不经过网关），进程里<b>没有</b> user-center 可调：
 * <ul>
 *   <li>下单链路要读地址/购物车（{@code OrderServiceImpl} → {@code AddressQueryService}/{@code CartCheckoutService}）；</li>
 *   <li>后台会员列表/详情/状态（{@code AdminMemberServiceImpl}）；</li>
 *   <li>订单列表补会员名、评价昵称、发券记录（{@code MemberQueryService}）；</li>
 *   <li>登录态兜底解析（{@code MemberSession} → {@code MemberQueryService.snapshot}）。</li>
 * </ul>
 * 没有替身，这些套件要么全部起不来（缺 bean），要么必须依赖"本机正好开着 user-center 服务"
 * （测试不能有这种外部依赖）。
 *
 * <h2>为什么是"直读 {@code mall_user}"而不是别的做法</h2>
 * <ul>
 *   <li><b>这是测试专用耦合</b>：本类只存在于 {@code src/test/java}，不会被打进任何产物。
 *       它把"会员数据现在在 {@code mall_user}"这个事实显式写下来——与 {@code MySqlTestBase}
 *       用 SQL 造会员是同一件事的两个方向（写 fixture / 读 fixture）；</li>
 *   <li><b>不用 mock</b>：这些套件断言的是真库行为（下单是否真的落单、库存是否真的扣减），
 *       会员数据被 mock 掉之后，{@code OrderServiceImpl} 拿到的地址/明细就是假数据，测出来的东西没有意义；</li>
 *   <li><b>不用"起一个 user-center"</b>：那是部署形态（见 {@code .dsh-notes/p3-cutover.ps1}），
 *       测试不该要求外部服务在线。</li>
 * </ul>
 * 语义<b>逐条对齐</b> user-center 的实现（逻辑删除/状态过滤、默认地址排序、claim 幂等 +
 * Redis 领取记录键 {@code mall:idem:cart:claim:{memberId}:{orderNo}}），
 * 否则会出现"本地测试绿、生产 401/404"这类两边不一致的坑。
 * 生产侧真正的远程实现由 {@code app.UserCenterRemoteConfigTest}（mock 客户端）钉住。
 *
 * <p>接线方式：本类通过 {@code src/test/resources/META-INF/spring/…AutoConfiguration.imports}
 * 自动生效——这样每个 {@code @SpringBootTest} 上下文都拿得到（不依赖测试类是否继承 MySqlTestBase）；
 * 而远程模式（{@code mall.user-center.remote=true}）下由
 * {@link ConditionalOnMissingBean} 让位给 {@code UserCenterRemoteConfig}。
 */
@AutoConfiguration
public class UserCenterTestDoubleConfig {

    @Bean
    @ConditionalOnMissingBean
    public MemberQueryService memberQueryService(JdbcTemplate jdbcTemplate) {
        return new JdbcMemberQuery(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemberAdminService memberAdminService(JdbcTemplate jdbcTemplate,
                                                TokenVersionService tokenVersionService,
                                                MemberStatusCache memberStatusCache) {
        return new JdbcMemberAdmin(jdbcTemplate, tokenVersionService, memberStatusCache);
    }

    @Bean
    @ConditionalOnMissingBean
    public AddressQueryService addressQueryService(JdbcTemplate jdbcTemplate) {
        return new JdbcAddressQuery(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public CartCheckoutService cartCheckoutService(JdbcTemplate jdbcTemplate, CacheService cacheService) {
        return new JdbcCartCheckout(jdbcTemplate, cacheService);
    }

    // ==================== 会员（只读） ====================

    /**
     * 会员查询替身。
     *
     * <p>每条查询都带 {@code deleted = 0}——属主实现里 MyBatis-Plus 的 {@code @TableLogic} 会自动加这个条件，
     * 这里手写等价条件，避免"逻辑删除的会员在测试里还能查到"的假绿。
     */
    static class JdbcMemberQuery implements MemberQueryService {

        private static final String SNAPSHOT_COLUMNS =
                "id, username, nickname, phone, avatar, status, create_time";

        private static final RowMapper<MemberSnapshotVO> SNAPSHOT = (rs, i) -> {
            Timestamp createTime = rs.getTimestamp("create_time");
            return new MemberSnapshotVO(rs.getLong("id"), rs.getString("username"), rs.getString("nickname"),
                    rs.getString("phone"), rs.getString("avatar"), rs.getInt("status"),
                    createTime == null ? null : createTime.toLocalDateTime());
        };

        private final JdbcTemplate jdbc;

        JdbcMemberQuery(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public MemberBriefVO brief(Long memberId) {
            if (memberId == null) {
                return null;
            }
            List<MemberBriefVO> rows = jdbc.query(
                    "SELECT id, username, nickname, phone FROM mall_user.ums_member WHERE id = ? AND deleted = 0",
                    (rs, i) -> new MemberBriefVO(rs.getLong("id"), rs.getString("username"),
                            rs.getString("nickname"), rs.getString("phone")), memberId);
            return rows.isEmpty() ? null : rows.get(0);
        }

        @Override
        public List<MemberBriefVO> briefs(Collection<Long> memberIds) {
            if (memberIds == null || memberIds.isEmpty()) {
                return List.of();
            }
            return Jdbc.byIds(jdbc,
                    "SELECT id, username, nickname, phone FROM mall_user.ums_member"
                            + " WHERE deleted = 0 AND id IN (%s)",
                    memberIds,
                    (rs, i) -> new MemberBriefVO(rs.getLong("id"), rs.getString("username"),
                            rs.getString("nickname"), rs.getString("phone")));
        }

        @Override
        public long count() {
            Long n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mall_user.ums_member WHERE deleted = 0", Long.class);
            return n == null ? 0L : n;
        }

        @Override
        public MemberSnapshotVO snapshot(Long memberId) {
            if (memberId == null) {
                return null;
            }
            List<MemberSnapshotVO> rows = jdbc.query(
                    "SELECT " + SNAPSHOT_COLUMNS + " FROM mall_user.ums_member WHERE id = ? AND deleted = 0",
                    SNAPSHOT, memberId);
            return rows.isEmpty() ? null : rows.get(0);
        }

        @Override
        public PageResult<MemberSnapshotVO> page(String keyword, Integer status,
                                                 LocalDateTime createTimeStart, LocalDateTime createTimeEnd,
                                                 long pageNum, long pageSize) {
            long page = Math.min(Math.max(pageNum, 1L), 10_000L);
            long size = Math.min(Math.max(pageSize, 1L), 50L);

            StringBuilder where = new StringBuilder(" WHERE deleted = 0");
            List<Object> args = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                where.append(" AND (username LIKE CONCAT('%', ?, '%')")
                        .append(" OR phone LIKE CONCAT('%', ?, '%')")
                        .append(" OR nickname LIKE CONCAT('%', ?, '%'))");
                args.add(keyword);
                args.add(keyword);
                args.add(keyword);
            }
            if (status != null) {
                where.append(" AND status = ?");
                args.add(status);
            }
            if (createTimeStart != null) {
                where.append(" AND create_time >= ?");
                args.add(Timestamp.valueOf(createTimeStart));
            }
            if (createTimeEnd != null) {
                where.append(" AND create_time < ?");
                args.add(Timestamp.valueOf(createTimeEnd));
            }

            Long total = jdbc.queryForObject("SELECT COUNT(*) FROM mall_user.ums_member" + where,
                    Long.class, args.toArray());
            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add((page - 1) * size);
            pageArgs.add(size);
            List<MemberSnapshotVO> list = jdbc.query(
                    "SELECT " + SNAPSHOT_COLUMNS + " FROM mall_user.ums_member" + where
                            + " ORDER BY create_time DESC LIMIT ?, ?",
                    SNAPSHOT, pageArgs.toArray());
            return PageResult.of(total == null ? 0L : total, page, size, list);
        }

        @Override
        public List<Long> searchIds(String keyword) {
            if (keyword == null || keyword.isBlank()) {
                return List.of();
            }
            return jdbc.queryForList("SELECT id FROM mall_user.ums_member WHERE deleted = 0"
                            + " AND (username LIKE CONCAT('%', ?, '%') OR phone LIKE CONCAT('%', ?, '%')"
                            + " OR nickname LIKE CONCAT('%', ?, '%'))",
                    Long.class, keyword, keyword, keyword);
        }
    }

    // ==================== 会员（管理写） ====================

    /** 会员状态写替身：与属主实现同序（校验 → 落库 → 清状态缓存 → 禁用时令牌版本 +1） */
    static class JdbcMemberAdmin implements MemberAdminService {

        private final JdbcTemplate jdbc;
        private final TokenVersionService tokenVersionService;
        private final MemberStatusCache memberStatusCache;

        JdbcMemberAdmin(JdbcTemplate jdbc, TokenVersionService tokenVersionService,
                        MemberStatusCache memberStatusCache) {
            this.jdbc = jdbc;
            this.tokenVersionService = tokenVersionService;
            this.memberStatusCache = memberStatusCache;
        }

        @Override
        public void updateStatus(Long memberId, Integer status) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mall_user.ums_member WHERE id = ? AND deleted = 0",
                    Integer.class, memberId);
            if (exists == null || exists == 0) {
                throw new BusinessException(404, "会员不存在");
            }
            if (status == null || (status != EnableStatus.DISABLED && status != EnableStatus.ENABLED)) {
                throw new BusinessException(400, "状态值仅支持 0禁用 1正常");
            }
            jdbc.update("UPDATE mall_user.ums_member SET status = ? WHERE id = ?", status, memberId);
            memberStatusCache.evict(memberId);
            if (status == EnableStatus.DISABLED) {
                // 禁用即失效：令牌版本 +1，旧 token 立刻不可用（与属主实现同口径）
                tokenVersionService.bump(TokenVersionService.TYPE_USER, memberId);
            }
        }
    }

    // ==================== 地址（只读） ====================

    /** 地址只读替身：默认地址排序规则与归属校验口径都与属主实现一致 */
    static class JdbcAddressQuery implements AddressQueryService {

        private static final String SELECT = "SELECT id, member_id, receiver_name, receiver_phone,"
                + " province_name, city_name, district_name, detail FROM mall_user.ums_address";

        private static final RowMapper<AddressSnapshotVO> MAPPER = (rs, i) -> {
            AddressSnapshotVO vo = new AddressSnapshotVO();
            vo.setId(rs.getLong("id"));
            vo.setMemberId(rs.getLong("member_id"));
            vo.setReceiverName(rs.getString("receiver_name"));
            vo.setReceiverPhone(rs.getString("receiver_phone"));
            vo.setProvinceName(rs.getString("province_name"));
            vo.setCityName(rs.getString("city_name"));
            vo.setDistrictName(rs.getString("district_name"));
            vo.setDetail(rs.getString("detail"));
            return vo;
        };

        private final JdbcTemplate jdbc;

        JdbcAddressQuery(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public AddressSnapshotVO defaultAddress(Long memberId) {
            if (memberId == null) {
                return null;
            }
            List<AddressSnapshotVO> rows = jdbc.query(SELECT
                            + " WHERE member_id = ? ORDER BY is_default DESC, update_time DESC LIMIT 1",
                    MAPPER, memberId);
            return rows.isEmpty() ? null : rows.get(0);
        }

        @Override
        public AddressSnapshotVO address(Long memberId, Long addressId) {
            if (memberId == null || addressId == null) {
                return null;
            }
            List<AddressSnapshotVO> rows = jdbc.query(SELECT + " WHERE id = ? AND member_id = ?",
                    MAPPER, addressId, memberId);
            return rows.isEmpty() ? null : rows.get(0);
        }
    }

    // ==================== 购物车（结算闸门） ====================

    /**
     * 购物车结算替身：claim/restore 两阶段，语义与属主实现逐条对齐。
     *
     * <p>领取记录用**同一个 Redis 键**（{@code mall:idem:cart:claim:{memberId}:{orderNo}}，TTL 24h）
     * 与同一份载荷形状（含 {@code spuId}：归还时 {@code spu_id} 是 NOT NULL，而对外快照里刻意没有它）。
     * 键前缀与属主实现一致是有意的——测试里清理同一个前缀即可。
     *
     * <p>{@code claim} 加 {@code synchronized}：测试里"两个线程抢同一批明细"是真实场景
     * （见 {@code ConcurrentStockMySqlTest}），而单条 DELETE 的影响行数在 MySQL 里同样是原子的——
     * 这里的锁只是让"读明细 → 删明细"这两步在同一个替身里不交错。
     */
    static class JdbcCartCheckout implements CartCheckoutService {

        private static final String CLAIM_KEY_PREFIX = "mall:idem:cart:claim:";
        private static final Duration CLAIM_TTL = Duration.ofHours(24);

        private final JdbcTemplate jdbc;
        private final CacheService cacheService;

        JdbcCartCheckout(JdbcTemplate jdbc, CacheService cacheService) {
            this.jdbc = jdbc;
            this.cacheService = cacheService;
        }

        @Override
        public List<CartItemSnapshotVO> items(Long memberId, Collection<Long> itemIds) {
            return targetRows(memberId, itemIds).stream()
                    .map(row -> new CartItemSnapshotVO(row.itemId(), row.skuId(), row.quantity()))
                    .toList();
        }

        @Override
        public boolean consumeItems(Long memberId, Collection<Long> itemIds) {
            // 与远程实现同口径：旧闸门语义依赖"远程删除会被本地事务回滚"，跨进程后不成立
            throw new UnsupportedOperationException(
                    "consumeItems 在远程模式下不可用：请改用两阶段闸门 claim/restore");
        }

        @Override
        public synchronized CartClaimResultVO claim(Long memberId, String orderNo, Collection<Long> itemIds) {
            if (memberId == null || orderNo == null || orderNo.isBlank() || itemIds == null || itemIds.isEmpty()) {
                return CartClaimResultVO.notClaimed();
            }
            String key = CLAIM_KEY_PREFIX + memberId + ":" + orderNo;
            // ① 幂等重放：同一订单再次调用，直接回放当时领到的明细（双击提交不该被判成冲突）
            List<ClaimedItem> previous = cacheService.getJson(key, new TypeReference<List<ClaimedItem>>() {
            });
            if (previous != null) {
                return new CartClaimResultVO(true, previous.stream().map(ClaimedItem::toSnapshot).toList());
            }
            // ② 首次领取：先读明细（归还快照），再条件删除——影响行数就是并发凭证
            List<ClaimedItem> rows = targetRows(memberId, itemIds);
            if (rows.size() != distinctSize(itemIds)) {
                return CartClaimResultVO.notClaimed();
            }
            int removed = 0;
            for (ClaimedItem row : rows) {
                removed += jdbc.update("DELETE FROM mall_user.ums_cart_item WHERE id = ? AND member_id = ?",
                        row.itemId(), memberId);
            }
            if (removed != rows.size()) {
                return CartClaimResultVO.notClaimed();   // 读与删之间被别的订单领走了
            }
            cacheService.setJson(key, rows, CLAIM_TTL);
            return new CartClaimResultVO(true, rows.stream().map(ClaimedItem::toSnapshot).toList());
        }

        @Override
        public boolean restore(Long memberId, String orderNo) {
            if (memberId == null || orderNo == null || orderNo.isBlank()) {
                return false;
            }
            String key = CLAIM_KEY_PREFIX + memberId + ":" + orderNo;
            List<ClaimedItem> claimed = cacheService.getJson(key, new TypeReference<List<ClaimedItem>>() {
            });
            if (claimed == null) {
                return false;   // 没领过（或已归还）：空操作
            }
            for (ClaimedItem item : claimed) {
                Integer same = jdbc.queryForObject("SELECT COUNT(*) FROM mall_user.ums_cart_item"
                        + " WHERE member_id = ? AND sku_id = ?", Integer.class, memberId, item.skuId());
                if (same != null && same > 0) {
                    continue;   // 唯一键 uk_member_sku：已存在即视为已归还
                }
                jdbc.update("INSERT INTO mall_user.ums_cart_item (member_id, spu_id, sku_id, quantity, checked)"
                                + " VALUES (?, ?, ?, ?, ?)",
                        memberId, item.spuId(), item.skuId(), item.quantity(), item.checked());
            }
            cacheService.delete(key);   // 归还后清掉领取记录，避免重复归还
            return true;
        }

        private List<ClaimedItem> targetRows(Long memberId, Collection<Long> itemIds) {
            if (memberId == null || itemIds == null || itemIds.isEmpty()) {
                return List.of();
            }
            return Jdbc.byIds(jdbc,
                    "SELECT id, spu_id, sku_id, quantity, checked FROM mall_user.ums_cart_item"
                            + " WHERE member_id = " + memberId + " AND id IN (%s)",
                    itemIds,
                    (rs, i) -> new ClaimedItem(rs.getLong("id"), rs.getLong("spu_id"),
                            rs.getLong("sku_id"), rs.getInt("quantity"), rs.getInt("checked")));
        }

        private static int distinctSize(Collection<Long> itemIds) {
            return (int) itemIds.stream().distinct().count();
        }

        /** 与属主实现同形状的领取记录（比对外快照多一个 spuId，用于归还） */
        public record ClaimedItem(long itemId, long spuId, long skuId, int quantity, int checked) {

            CartItemSnapshotVO toSnapshot() {
                return new CartItemSnapshotVO(itemId, skuId, quantity);
            }
        }
    }

    // ==================== SQL helpers ====================

    /** 动态 {@code IN} 查询的小工具（占位符按 id 个数生成，参数走绑定，不拼字符串值） */
    static final class Jdbc {

        private Jdbc() {
        }

        static <T> List<T> byIds(JdbcTemplate jdbc, String sqlTemplate, Collection<Long> ids, RowMapper<T> mapper) {
            Set<Long> distinct = new LinkedHashSet<>();
            for (Long id : ids) {
                if (id != null) {
                    distinct.add(id);
                }
            }
            if (distinct.isEmpty()) {
                return List.of();
            }
            String placeholders = String.join(", ", distinct.stream().map(id -> "?").toList());
            return jdbc.query(sqlTemplate.formatted(placeholders), mapper, distinct.toArray());
        }
    }
}
