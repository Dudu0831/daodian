package com.abc.daodian.card.assistant

/**
 * 给模型照着学的几张样板卡（§8.5）：设计稿 11-19 乙那一列（<https://claude.ai/artifact/UBWFbah7cWdiytN6RnnZ9B>）落成能跑的网页。
 * 每张都有一样只属于这件事的画面（航线和小飞机、整张朱砂和福字、毛笔柱子、比例带、进度环、时间线），模型照这个思路给别的事想。
 * 编号是假的：#12 去新加坡（带行李清单）、#20 春节、#8 交房租。`CardTest` 拿它们过闸门；`CardSampleTest` 把它们填上假数写成网页，
 * 浏览器里打开就能看（`build/card-samples/`）。
 *
 * 写的时候守和模型一样的规矩：数一律绑、装饰加 data-bg、尺寸用百分比和 flex（桌面拖大拖小照样排得开）、不用 emoji、不写做不到的事。
 * Kotlin 的原样字符串里 `$` 会被当成插值，网页里别出现
 */
internal object CardSamples {

    class Sample(val what: String, val size: String, val html: String)

    val ALL: List<Sample> = listOf(

        Sample(
            "出门倒数 + 行李清单（一条航线、一架小飞机、一枚印章；清单每行能点）", "4x2", """
<div class="c">
  <svg class="route" data-bg viewBox="0 0 100 30" preserveAspectRatio="none"><path d="M2 26 C 30 2, 70 0, 97 12"/></svg>
  <svg class="plane" data-bg viewBox="0 0 24 24"><path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z"/></svg>
  <div class="l">
    <div class="big" id="days">{{reminder.days_left #12}}</div>
    <div class="sub">天后 · {{reminder.title #12}}</div>
    <div class="cap">{{reminder.date #12}} {{reminder.weekday #12}}</div>
  </div>
  <div class="r">
    <div class="stamp">出发</div>
    <div class="h">行李 · 还差 {{reminder.items_left #12}} 样</div>
    <div class="list" id="list"></div>
  </div>
</div>
<style>
.c{position:relative;height:100%;padding:14px 16px 12px;display:flex;gap:16px}
.route{position:absolute;left:16px;right:16px;top:10px;width:calc(100% - 32px);height:30%;fill:none;stroke:var(--rule2);stroke-width:1.4;stroke-dasharray:1.5 4;stroke-linecap:round;vector-effect:non-scaling-stroke}
.plane{position:absolute;left:40%;top:7%;width:15px;height:15px;fill:var(--ink2);transform:rotate(78deg)}
.l{position:relative;width:44%;display:flex;flex-direction:column;justify-content:flex-end}
.big{font:900 68px/0.86 var(--serif);letter-spacing:-3px}
.sub{font:14px var(--serif);color:var(--ink2);margin-top:8px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.cap{font-size:10.5px;color:var(--muted);letter-spacing:.06em;margin-top:3px}
.r{position:relative;flex:1;min-width:0;display:flex;flex-direction:column}
.stamp{align-self:flex-end;border:1.5px solid var(--accent);color:var(--accent);font:600 12px var(--serif);letter-spacing:.2em;padding:1px 4px 1px 7px;border-radius:3px;transform:rotate(7deg);margin:-4px 0 8px}
.h{font-size:10.5px;color:var(--muted);letter-spacing:.06em;margin-bottom:2px}
.list{flex:1;overflow:hidden}
.row{display:flex;align-items:center;gap:8px;height:23px;font-size:13px;white-space:nowrap}
.row i{flex:none;width:11px;height:11px;border:1.2px solid var(--ink2);border-radius:2px}
.row.done{color:var(--hint)}.row.done i{background:var(--hint);border-color:var(--hint)}
</style>
<script>(function(){
  if (D["reminder.near #12"]) document.getElementById('days').style.color = 'var(--accent)';
  var it = D["reminder.items #12"], box = document.getElementById('list');
  var rows = (it ? it.rows : []).slice().sort(function(a, b){ return a.done - b.done; });
  var room = Math.max(1, Math.floor(box.clientHeight / 23));
  rows.slice(0, room).forEach(function(r){
    var d = document.createElement('div');
    d.className = 'row' + (r.done ? ' done' : '');
    if (r.tap) d.setAttribute('data-tap', r.tap);
    d.appendChild(document.createElement('i'));
    d.appendChild(document.createTextNode(r.title));
    box.appendChild(d);
  });
})();</script>
""".trim()
        ),

        Sample(
            "节日倒数（整张铺朱砂，一个淡淡的大字垫在后面）", "2x2", """
<div class="c">
  <div class="mark" data-bg>福</div>
  <div class="t">离{{reminder.title #20}}</div>
  <div class="n"><b>{{reminder.days_left #20}}</b><span>天</span></div>
  <div class="d">{{reminder.date #20}} · {{reminder.weekday #20}}</div>
</div>
<style>
.c{position:relative;height:100%;padding:16px 16px 14px;background:var(--accent);color:#FFF6EC;display:flex;flex-direction:column;overflow:hidden}
.mark{position:absolute;right:-14%;bottom:-22%;font:900 150px/1 var(--serif);color:#FFFFFF;opacity:.12;transform:rotate(-8deg)}
.t{position:relative;font:14px var(--serif);letter-spacing:.25em;opacity:.9}
.n{position:relative;flex:1;display:flex;align-items:center;gap:4px}
.n b{font:900 78px/1 var(--serif);letter-spacing:-3px}.n span{font:15px var(--serif);margin-top:26px;opacity:.85}
.d{position:relative;font-size:10.5px;letter-spacing:.08em;opacity:.8}
</style>
""".trim()
        ),

        Sample(
            "这周花了（毛笔笔触的柱子，一条上周日均的虚线，今天那根朱砂）", "4x2", """
<div class="c">
  <div class="l">
    <div class="t">这周花了</div>
    <div class="n"><small>¥</small>{{ledger.spent week}}</div>
    <div class="cap">{{ledger.count week}} 笔 · 上周 ¥{{ledger.spent last_week}}</div>
  </div>
  <svg class="chart" id="chart"></svg>
</div>
<style>
.c{height:100%;padding:14px 16px 10px;display:flex;gap:14px}
.l{width:36%;display:flex;flex-direction:column}
.t{font:600 14px var(--serif);letter-spacing:.12em}
.n{flex:1;display:flex;align-items:center;font:600 40px/1 var(--serif);letter-spacing:-1px}
.n small{font-size:16px;color:var(--muted);margin-right:3px;letter-spacing:0}
.cap{font-size:10.5px;color:var(--muted);letter-spacing:.05em}
.chart{flex:1;min-width:0;height:100%;overflow:visible}
</style>
<script>(function(){
  var s = D["ledger.daily week"] || {values: [], labels: [], current: -1};
  var avg = D["ledger.per_day last_week"] || 0;
  var svg = document.getElementById('chart'), W = svg.clientWidth, H = svg.clientHeight;
  var NS = 'http://www.w3.org/2000/svg', base = H - 16, top = 14, n = s.values.length || 7, step = W / n;
  var max = Math.max.apply(null, s.values.concat([avg, 1]));
  function el(tag, attrs, text){ var e = document.createElementNS(NS, tag); for (var k in attrs) e.setAttribute(k, attrs[k]); if (text) e.textContent = text; svg.appendChild(e); return e; }
  el('line', {x1: 0, x2: W, y1: base + 0.5, y2: base + 0.5, stroke: 'var(--rule)'});
  s.values.forEach(function(v, i){
    var x = i * step + step / 2, future = s.current >= 0 && i > s.current, now = i === s.current;
    if (future || v <= 0) {
      el('line', {x1: x, x2: x, y1: base - 10, y2: base, stroke: 'var(--rule2)', 'stroke-dasharray': '1 3'});
    } else {
      var h = Math.max(3, (base - top) * v / max), w = Math.min(18, step * 0.56);
      // 毛笔：两边略微收腰、顶上一点歪
      el('path', {d: 'M' + (x - w/2) + ' ' + base + ' q1.5 ' + (-h * 0.5) + ' 0.5 ' + (-h + 2) + ' q' + (w/2) + ' -4 ' + w + ' 1 q-1 ' + (h * 0.5) + ' 0.5 ' + (h - 3) + 'z',
        fill: now ? 'var(--accent)' : 'var(--ink2)'});
    }
    el('text', {x: x, y: H - 2, 'text-anchor': 'middle', 'font-size': 9.5, fill: now ? 'var(--accent)' : 'var(--muted)'}, s.labels[i] || '');
  });
  if (avg > 0) {
    var ay = base - (base - top) * avg / max;
    el('line', {x1: 0, x2: W, y1: ay, y2: ay, stroke: 'var(--muted)', 'stroke-dasharray': '3 3', 'stroke-width': 0.8, 'data-bg': ''});
    el('text', {x: W, y: ay - 4, 'text-anchor': 'end', 'font-size': 9, fill: 'var(--muted)'}, '上周日均 ¥' + avg);
  }
})();</script>
""".trim()
        ),

        Sample(
            "这个月钱花哪了（一条比例带 + 排行，比例按每行的 value 算）", "4x4", """
<div class="c">
  <div class="top"><span class="t">这个月 · 钱花哪了</span><span class="sum"><small>¥</small>{{ledger.spent month}}</span></div>
  <div class="band" id="band"></div>
  <div class="list" id="list"></div>
  <div class="foot">{{ledger.count month}} 笔 · 平均每天 ¥{{ledger.per_day month}}</div>
</div>
<style>
.c{height:100%;padding:16px 18px 14px;display:flex;flex-direction:column}
.top{display:flex;justify-content:space-between;align-items:baseline}
.t{font:600 14px var(--serif);letter-spacing:.12em}
.sum{font:600 30px/1 var(--serif)}.sum small{font-size:14px;color:var(--muted);margin-right:2px}
.band{display:flex;height:10px;border-radius:5px;overflow:hidden;margin:14px 0 16px;background:var(--rule)}
.band i{display:block;height:100%}
.list{flex:1;overflow:hidden;display:flex;flex-direction:column;gap:11px}
.row{display:grid;grid-template-columns:9px 1fr auto;column-gap:9px;align-items:center;font-size:13px}
.row b{width:9px;height:9px;border-radius:2px}
.row .bar{grid-column:2 / 4;height:3px;border-radius:2px;background:var(--rule);margin-top:5px;overflow:hidden}
.row .bar i{display:block;height:100%}
.row em{font-style:normal;color:var(--ink2);font-variant-numeric:tabular-nums}
.foot{font-size:10.5px;color:var(--muted);letter-spacing:.05em;margin-top:8px}
.empty{color:var(--muted);font-size:12px}
</style>
<script>(function(){
  var it = D["ledger.by_category month"], rows = it ? it.rows : [];
  var tones = ['var(--accent)', 'var(--ink)', 'var(--ink2)', 'var(--muted)', 'var(--hint)', 'var(--rule2)'];
  var total = rows.reduce(function(a, r){ return a + (r.value || 0); }, 0);
  var band = document.getElementById('band'), list = document.getElementById('list');
  if (!total) { list.innerHTML = '<div class="empty">这个月还没有支出</div>'; return; }
  rows.forEach(function(r, i){
    var seg = document.createElement('i');
    seg.style.width = (r.value / total * 100) + '%';
    seg.style.background = tones[Math.min(i, tones.length - 1)];
    band.appendChild(seg);
  });
  var room = Math.max(1, Math.floor((list.clientHeight + 11) / 35));
  rows.slice(0, room).forEach(function(r, i){
    var tone = tones[Math.min(i, tones.length - 1)], d = document.createElement('div');
    d.className = 'row';
    d.innerHTML = '<b style="background:' + tone + '"></b><span></span><em></em><div class="bar"><i style="width:' + (r.value / rows[0].value * 100) + '%;background:' + tone + '"></i></div>';
    d.children[1].textContent = r.title;
    d.children[2].textContent = r.note;
    list.appendChild(d);
  });
})();</script>
""".trim()
        ),

        Sample(
            "离那天走了多远（一个环，环里是天数）", "2x2", """
<div class="c">
  <div class="ring">
    <svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="44" class="bg"/><circle cx="50" cy="50" r="44" class="fg" id="arc"/></svg>
    <div class="mid"><b>{{reminder.days_left #12}}</b><span>天</span></div>
  </div>
  <div class="t">{{reminder.title #12}}</div>
  <div class="d">{{reminder.date #12}} {{reminder.weekday #12}}</div>
</div>
<style>
.c{height:100%;padding:12px 12px 10px;display:flex;flex-direction:column;align-items:center}
.ring{position:relative;flex:1;min-height:0;aspect-ratio:1}
.ring svg{width:100%;height:100%;transform:rotate(-90deg)}
.ring circle{fill:none;stroke-width:5}
.ring .bg{stroke:var(--rule)}.ring .fg{stroke:var(--accent);stroke-linecap:round}
.mid{position:absolute;inset:0;display:flex;align-items:baseline;justify-content:center;padding-top:30%}
.mid b{font:900 36px/1 var(--serif);letter-spacing:-1px}.mid span{font-size:11px;color:var(--muted);margin-left:2px}
.t{font:13px var(--serif);margin-top:6px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%}
.d{font-size:10px;color:var(--muted);letter-spacing:.05em}
</style>
<script>(function(){
  var p = Math.max(0, Math.min(100, D["reminder.progress #12"] || 0)), len = 2 * Math.PI * 44;
  var arc = document.getElementById('arc');
  arc.setAttribute('stroke-dasharray', (len * p / 100) + ' ' + len);
  if (p < 1) arc.style.display = 'none';
})();</script>
""".trim()
        ),

        Sample(
            "接下来（一根时间线，离得越远点越淡；每条行尾的圈能点完成）", "4x4", """
<div class="c">
  <div class="top"><span class="t">接下来</span><span class="d">{{time.date}} {{time.weekday}}</span></div>
  <div class="line" id="line"></div>
</div>
<style>
.c{height:100%;padding:16px 18px 12px;display:flex;flex-direction:column}
.top{display:flex;justify-content:space-between;align-items:baseline;margin-bottom:12px}
.t{font:600 15px var(--serif);letter-spacing:.2em}.d{font-size:10.5px;color:var(--muted);letter-spacing:.05em}
.line{position:relative;flex:1;overflow:hidden;padding-left:18px}
.line:before{content:'';position:absolute;left:4px;top:6px;bottom:0;border-left:1px dashed var(--rule2)}
.ev{position:relative;display:flex;align-items:center;height:52px}
.ev .dot{position:absolute;left:-18px;width:9px;height:9px;border-radius:50%;background:var(--ink2)}
.ev .txt{flex:1;min-width:0}
.ev .w{font-size:10.5px;color:var(--muted);letter-spacing:.05em}
.ev .n{font:15px var(--serif);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ev.soon .dot{background:var(--accent)}.ev.soon .w{color:var(--accent)}
.ev .ok{flex:none;width:26px;height:26px;display:flex;align-items:center;justify-content:center}
.ev .ok i{width:15px;height:15px;border-radius:50%;border:1.2px solid var(--rule2)}
.none{color:var(--muted);font-size:12px;padding-top:6px}
</style>
<script>(function(){
  var it = D["reminder.upcoming"], rows = it ? it.rows : [], box = document.getElementById('line');
  if (!rows.length) { box.innerHTML = '<div class="none">接下来没有事</div>'; return; }
  var room = Math.max(1, Math.floor(box.clientHeight / 52));
  rows.slice(0, room).forEach(function(r){
    var days = r.value == null ? 0 : r.value, e = document.createElement('div');
    e.className = 'ev' + (days <= 3 ? ' soon' : '');
    e.innerHTML = '<span class="dot"></span><div class="txt"><div class="w"></div><div class="n"></div></div><div class="ok"><i></i></div>';
    e.querySelector('.dot').style.opacity = Math.max(0.35, 1 - days / 60);
    e.querySelector('.w').textContent = days === 0 ? '今天 · ' + r.note : days === 1 ? '明天 · ' + r.note.replace('明天', '').trim() : days + ' 天后 · ' + r.note;
    e.querySelector('.n').textContent = r.title;
    if (r.tap) e.querySelector('.ok').setAttribute('data-tap', r.tap);
    box.appendChild(e);
  });
})();</script>
""".trim()
        )
    )
}
