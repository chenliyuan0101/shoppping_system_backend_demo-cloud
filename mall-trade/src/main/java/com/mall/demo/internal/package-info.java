/**
 * 内部接口（服务间调用专用）：把 P0 产出的「域服务接口」按域包成 HTTP 端点。
 *
 * <h2>为什么存在这层</h2>
 * P0 已经把跨域协作从"直连别人的表"改成了"调用对方的域服务接口"。抽服务时，
 * 这些接口要变成 HTTP 端点——本包就是<b>那一步的落点</b>：接口签名不变，只是多了一层 adapter。
 * 因此这层的每个方法都应当是<b>一行委托</b>，不含业务逻辑（有逻辑说明放错了地方）。
 *
 * <h2>约定（改动前请先读）</h2>
 * <ul>
 *   <li><b>路径</b>：{@code /internal/v1/**}。带版本号，因为内部接口可以演进，
 *       但不能无声破坏调用方。</li>
 *   <li><b>网关不路由</b>：{@code mall-gateway} 有过滤器把 {@code /internal/**} 直接 404——
 *       外网经网关永远看不到这些路径（见 {@code InternalPathBlockFilter}）。</li>
 *   <li><b>鉴权</b>：所有 {@code /internal/**} 都要求请求头 {@code X-Internal-Token}
 *       （见 {@link com.mall.demo.app.InternalApiAuthInterceptor}）；未配置密钥时一律拒绝（fail-closed）。</li>
 *   <li><b>响应体</b>：与对外接口一致，统一 {@code ApiResponse{code,message,data}}——
 *       调用方用的是同一套解析逻辑。</li>
 *   <li><b>只收发 DTO</b>：入参出参都是 {@code common.dto} 里的契约快照，
 *       禁止出现 Entity/Mapper/持久层类型（与 P0 的域服务接口同一条规矩）。</li>
 *   <li><b>幂等</b>：写接口（扣库存、核销券、清购物车、抢评价标记）都以业务键
 *       （{@code orderNo}/{@code orderItemId}）幂等，重复调用无副作用——这是补偿重试的前提。</li>
 * </ul>
 *
 * <h2>现状</h2>
 * 已暴露 product / marketing / trade 三个域的跨域接口。
 * 每抽出一个服务，就把它需要的内部端点补齐（本包按域一个 controller，加方法即可）；
 * 不做"为想象中的调用方预埋 API"——P0 的教训是契约要按真实需求生长。
 *
 * <p><b>P3-4</b>：原 {@code user} 域的 {@code InternalUserController}（{@code /internal/v1/user/**}）
 * 已整体平移到 {@code mall-user-center}（会员/地址/购物车数据的属主在那里）——
 * 单体这边删掉，@MemberId 的身份解析仍然照常（见 {@code auth.support.MemberSession}：
 * 网关注入身份 → 快路径；自行验签 → 域契约 {@code common.contract.MemberQueryService}）。
 */
package com.mall.demo.internal;
