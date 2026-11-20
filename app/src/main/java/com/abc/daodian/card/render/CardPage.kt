package com.abc.daodian.card.render

import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.card.spec.Ref
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 模型写的那一段网页 + 这一刻的数 → 一整页，交给 [CardShot] 截图。不碰 Android，单元测试直接跑。见 DESIGN.md §8.5
 *
 * 外壳管四样：
 * - 断网（CSP 只许内联的样式和脚本、`data:` 的图），和对话里画图那个沙盒一样严（`assistant/conversation/render/WidgetSandbox`）；
 * - 尺寸：页面就是卡片那么大（CSS 的 px = dp），`html`、`body` 钉死宽高、超出的裁掉；
 * - 颜色：墨宋那几样做成 CSS 变量（`--ink`、`--accent`…），深浅色各截一张，模型用变量就跟着变；
 * - 数：字里的 `{{名字}}` 换成那个值的字（转义过），脚本里的 `D["名字"]` 是那个值的 JSON（[json]）。
 * 页面加载完由 [CardShot] 叫 `__measure()` 量一遍：宽高超出多少、是不是空的、能点的那几块在哪（`data-tap`）、脚本有没有报错。
 */
object CardPage {

    /** 墨宋的几样颜色，和 `res/values` 和 `res/values-night` 两份 colors.xml 里的 `widget_*` 是同一套，改一边改另一边 */
    data class Palette(
        val paper: String, val surface: String, val ink: String, val ink2: String, val muted: String,
        val hint: String, val rule: String, val rule2: String, val accent: String, val dark: Boolean
    )

    val LIGHT = Palette("#F2EFE6", "#F8F6EF", "#1C1A17", "#3A372F", "#8A8578", "#A9A395", "#DFDACC", "#C9C3B3", "#9E3B2E", dark = false)
    val DARK = Palette("#14130F", "#1C1A15", "#EDEAE0", "#C3BEB0", "#7C7869", "#6E6A5E", "#2E2B23", "#38352C", "#C4604F", dark = true)

    private val mapper = ObjectMapper()

    private val DATA = Regex("""\bD\s*\[\s*(["'])([^"']+)\1\s*\]""")

    /** 绑定取不到值时字里写什么 */
    const val MISSING = "—"

    /**
     * 拼一整页。[value] 按名字取这一刻的值（取不到 null）。[width]×[height] 是 dp
     */
    suspend fun build(html: String, width: Float, height: Float, palette: Palette, value: suspend (Ref) -> CardValue?): String {
        // 脚本里用到的每个 D["…"]：键照模型写的原样（空格多一个少一个都对得上）
        val data = LinkedHashMap<String, Any?>()
        DATA.findAll(html).map { it.groupValues[2] }.distinct().forEach { raw -> data[raw] = Ref.parse(raw)?.let { json(value(it)) } }
        val body = Ref.fill(html) { ref -> escape(ref?.let { value(it) }?.asText() ?: MISSING) }
        val w = fmt(width)
        val h = fmt(height)
        val p = palette
        // </script> 出现在数据里会提前关掉脚本
        val d = mapper.writeValueAsString(data).replace("</", "<\\/")
        return """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; font-src data:">
<style>
:root{--paper:${p.paper};--surface:${p.surface};--ink:${p.ink};--ink2:${p.ink2};--muted:${p.muted};--hint:${p.hint};
--rule:${p.rule};--rule2:${p.rule2};--accent:${p.accent};--serif:"Noto Serif CJK SC","Source Han Serif SC",serif;--sans:sans-serif;
--w:${w}px;--h:${h}px;color-scheme:${if (p.dark) "dark" else "light"}}
*{box-sizing:border-box}
html,body{margin:0;padding:0;width:${w}px;height:${h}px;overflow:hidden;background:var(--paper);color:var(--ink);
font:14px/1.4 var(--sans);-webkit-font-smoothing:antialiased;font-variant-numeric:tabular-nums}
</style>
<script>var __t0=Date.now(),__errs=[];
window.onerror=function(m,s,l){__errs.push(String(m)+(l?' @'+l:''));};
var D=$d;</script>
</head><body>
$body
<script>
// CardShot 在页面加载完后自己来叫（evaluateJavascript），不靠定时器：看不见的 WebView 里定时器不一定走
window.__measure=function(){
 var W=$w,H=$h,de=document.documentElement,b=document.body,over=[];
 // 垫在后面的装饰（data-bg、绝对定位）故意压出边、被裁掉（很大很淡的「春」字），不算超出：量之前先藏起来。
 // 只藏不占位置的那种，藏了别的东西不挪；占位置的照量
 var bgs=[].filter.call(b.querySelectorAll('[data-bg]'),function(e){var p=getComputedStyle(e).position;return p=='absolute'||p=='fixed'}),bgd=bgs.map(function(e){return e.style.display});
 bgs.forEach(function(e){e.style.display='none'});
 var sw=Math.max(de.scrollWidth,b.scrollWidth),sh=Math.max(de.scrollHeight,b.scrollHeight);
 var r=0,bt=0;[].forEach.call(b.querySelectorAll('*'),function(e){var q=e.getBoundingClientRect();if(q.width>0&&q.height>0){r=Math.max(r,q.right);bt=Math.max(bt,q.bottom)}});
 bgs.forEach(function(e,i){e.style.display=bgd[i]});
 sw=Math.max(sw,Math.round(r));sh=Math.max(sh,Math.round(bt));
 if(sw>W+1)over.push('宽出 '+Math.round(sw-W)+'px');if(sh>H+1)over.push('高出 '+Math.round(sh-H)+'px');
 var empty=!(b.innerText||'').trim()&&!b.querySelector('svg,canvas,img');
 // 能点的每一块，和它里面那个方框 / 圈的中心（桌面当场画勾画在那儿）：先认 data-box，没有就找头一个小方块
 var taps=[].map.call(b.querySelectorAll('[data-tap]'),function(e){var q=e.getBoundingClientRect(),m=e.querySelector('[data-box]');
  if(!m)m=[].filter.call(e.querySelectorAll('*'),function(c){var k=c.getBoundingClientRect();return k.width>=6&&k.width<=28&&k.height>=6&&k.height<=28&&Math.abs(k.width-k.height)<=4})[0];
  var k=m?m.getBoundingClientRect():null;
  return {a:e.getAttribute('data-tap'),x:q.left,y:q.top,w:q.width,h:q.height,bx:k?k.left+k.width/2:q.left+7,by:k?k.top+k.height/2:q.top+q.height/2}}).filter(function(t){return t.w>0&&t.h>0});
 // 互相压住的：字（只量字本身那一截）、svg、没有子元素的色块，两两比，叠住小的那块四分之一以上就报。
 // 一个在另一个里面的不算；标了 data-bg 的（垫在字后面的装饰）、半透明以下的不算
 function seen(e){var s=getComputedStyle(e);return s.display!='none'&&s.visibility!='hidden'&&parseFloat(s.opacity)>=0.5}
 var leaves=[];
 [].forEach.call(b.querySelectorAll('*'),function(e){
  if(e.closest('[data-bg]')||!seen(e))return;
  var tag=e.tagName.toLowerCase(),inSvg=e.parentNode&&e.parentNode.closest&&e.parentNode.closest('svg');
  if(inSvg||tag=='script'||tag=='style')return;
  var own=[].filter.call(e.childNodes,function(n){return n.nodeType==3&&n.textContent.trim()});
  var q=null,name='';
  if(own.length){var g=document.createRange();g.setStartBefore(own[0]);g.setEndAfter(own[own.length-1]);q=g.getBoundingClientRect();name='「'+own.map(function(n){return n.textContent.trim()}).join('').slice(0,12)+'」'}
  else if(tag=='svg'){q=e.getBoundingClientRect();name='一张 svg 图'}
  else if(!e.children.length){var bg=getComputedStyle(e).backgroundColor;if(bg&&bg!='transparent'&&!/rgba\([^)]*,\s*0\)/.test(bg)){q=e.getBoundingClientRect();name='一块色块'}}
  if(q&&q.width*q.height>12)leaves.push({e:e,q:q,n:name});
 });
 var hits=[];
 for(var i=0;i<leaves.length&&hits.length<3;i++)for(var j=i+1;j<leaves.length&&hits.length<3;j++){
  var a=leaves[i],c=leaves[j];if(a.e.contains(c.e)||c.e.contains(a.e))continue;
  var w=Math.min(a.q.right,c.q.right)-Math.max(a.q.left,c.q.left),h=Math.min(a.q.bottom,c.q.bottom)-Math.max(a.q.top,c.q.top);
  if(w<=1||h<=1)continue;var small=Math.min(a.q.width*a.q.height,c.q.width*c.q.height);
  if(w*h>small*0.25&&w*h>20)hits.push(a.n+'和'+c.n);
 }
 var text=(b.innerText||'').replace(/\s+/g,' ').trim().slice(0,200);
 return JSON.stringify({over:over,empty:empty,taps:taps,errors:__errs,overlaps:hits,text:text});
};
</script>
</body></html>
""".trimIndent()
    }

    /** 一个值在脚本里的样子 */
    fun json(v: CardValue?): Any? = when (v) {
        null -> null
        is CardValue.Text -> v.text
        is CardValue.Number -> v.value
        is CardValue.Flag -> v.on
        is CardValue.Rows -> linkedMapOf(
            "rows" to v.rows.map { r -> linkedMapOf("title" to r.title, "note" to r.note, "accent" to r.accent, "done" to r.done, "tap" to r.action, "value" to r.value) },
            "total" to v.total
        )
        is CardValue.Series -> linkedMapOf("values" to v.values, "labels" to v.labels, "current" to v.current)
        is CardValue.Moment -> linkedMapOf("at" to v.at, "label" to v.label)
    }

    fun escape(s: String): String = buildString(s.length) {
        s.forEach { c ->
            when (c) {
                '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private fun fmt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)
}
