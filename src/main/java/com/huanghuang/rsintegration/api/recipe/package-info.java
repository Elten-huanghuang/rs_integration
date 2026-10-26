/**
 * 配方投影公共契约。
 *
 * <p>第三方通过 {@link com.huanghuang.rsintegration.api.recipe.RecipeIntegration}
 * 把 live recipe 转换成 {@link com.huanghuang.rsintegration.api.recipe.RecipeView}。
 * 配方视图必须只包含真实、可枚举的材料和产物，不能保存世界对象。</p>
 */
@ApiStatus.AvailableSince("1.0.0")
package com.huanghuang.rsintegration.api.recipe;

import org.jetbrains.annotations.ApiStatus;
