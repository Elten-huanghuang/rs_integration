/**
 * RSI 公共 API 根包。
 *
 * <p>Integration API 从 {@link com.huanghuang.rsintegration.api.RSIIntegrationApi}
 * 开始；Consumer API 从
 * {@link com.huanghuang.rsintegration.api.consumer.RSICraftConsumerApi} 开始。
 * 只有公共包中未标记 {@link org.jetbrains.annotations.ApiStatus.Internal} 的类型
 * 属于第三方兼容契约。</p>
 */
@ApiStatus.AvailableSince("1.0.0")
package com.huanghuang.rsintegration.api;

import org.jetbrains.annotations.ApiStatus;
