package com.huanghuang.rsintegration.crafting;

/**
 * 显式声明配方可以按数量安全放大。
 *
 * <p>实现者必须保证单次执行没有随机性、全局副作用、动态 NBT 传播或
 * 未计入输入的余料。</p>
 */
public interface StableBatchRecipe {
}
