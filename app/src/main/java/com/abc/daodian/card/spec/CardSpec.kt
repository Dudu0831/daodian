package com.abc.daodian.card.spec

/**
 * 一张卡片的说明：模型写的一页网页，代码在后台截成图贴上桌面（`render/CardShot`）。见 DESIGN.md §8.5
 *
 * 只说「长什么样、每个数绑到哪」，不存数：数每次重画时按绑定现取（`CardReader`），填进网页再截。卡片自己什么都不记，
 * 每个数都绑提醒、记账里真有的那条。写法见 [CardSpecs]，给模型看的那一份在 `assistant/CardGuide`。
 */
data class CardSpec(
    /** 卡片叫什么（「去旧金山」）：对话里、痕上认它用，不画在卡片上 */
    val name: String,
    val size: CardSize,
    /** 模型写的网页（不带 html / head / body，由 `render/CardPage` 套外壳） */
    val html: String
)

/**
 * 桌面上的四种大小，一种一个桌面组件（`CardWidget` 的子类）。[key] 存进库、写在说明里，不改。
 * [widthDp]×[heightDp] 是对话里预览、模型排版时按的大小，和这台荣耀桌面上量出来的差不多
 */
enum class CardSize(val key: String, val label: String, val widthDp: Int, val heightDp: Int) {
    S2X2("2x2", "2×2", 164, 164),
    S4X1("4x1", "4×1", 352, 72),
    S4X2("4x2", "4×2", 352, 164),
    S4X4("4x4", "4×4", 352, 352);

    companion object {
        fun of(key: String?): CardSize? = entries.firstOrNull { it.key == key?.trim()?.lowercase()?.replace('×', 'x') }
    }
}
