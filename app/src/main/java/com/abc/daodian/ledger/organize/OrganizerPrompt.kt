package com.abc.daodian.ledger.organize

import com.abc.daodian.ledger.domain.CategoryNode
import com.abc.daodian.ledger.domain.LedgerText
import com.abc.daodian.ledger.domain.RawNote
import com.abc.daodian.ledger.domain.TxnBrief
import com.abc.daodian.ledger.tools.ListExpensesTool
import com.abc.daodian.ledger.tools.RecordExpensesTool
import com.abc.daodian.runtime.Prompt
import com.abc.daodian.runtime.memory.Memory
import java.time.ZoneId

/**
 * 记账整理员的提示词：system，和每一批喂给它的输入怎么拼（[input]）。助手那边怎么用记账工具，
 * 写在助手自己的提示词里（`assistant/AssistantPrompt`），不在这里。
 *
 * system 必须逐字节稳定（前缀缓存）：会变的东西（这批通知、类别表、商户记忆、关于他记下的事）
 * 一律放进那一轮的输入，不进这里。system 里「## 输入」那段说每次给它什么，[input] 就照那个拼，两边一起改。
 * 改了措辞就把 [VERSION] 加一 —— 它会写进每笔流水的 parsed_by，以后看账能知道是哪一版提示词整理的。
 */
object OrganizerPrompt {

    const val VERSION = "organize-v8"

    /** 设置里「整理员自己打标签」关着（默认）用它：一个标签都不打，标签只由用户打 */
    val SYSTEM: String = organize(tagging = false)

    /** 开着用它：照用户打过的学，只用已有的标签，有把握才打 */
    val SYSTEM_TAGGING: String = organize(tagging = true)

    /** 写进 parsed_by 的版本：开着打标签的那一版带「+tags」 */
    fun version(tagging: Boolean): String = if (tagging) "$VERSION+tags" else VERSION

    /** 照着写的工具：交流水、查最近的账（开不开打标签都是这两个，开着时 record_expenses 多一个 tags 参数） */
    private val TOOLS = setOf(RecordExpensesTool.NAME, ListExpensesTool.NAME)

    val PLAIN = Prompt(SYSTEM, version(tagging = false), TOOLS)

    val TAGGING = Prompt(SYSTEM_TAGGING, version(tagging = true), TOOLS)

    fun prompt(tagging: Boolean): Prompt = if (tagging) TAGGING else PLAIN

    private fun organize(tagging: Boolean): String = """
你是记账 app 里的整理员，在后台工作，用户看不到你说的话。你的活：把手机上收到的支付通知整理成流水。

## 输入
每次给你一批还没整理的原始通知（raw#编号），附带类别表、用户确认过的商户记忆、关于用户记下的事（他说过的，
比如哪张卡是谁在用、每月固定的那笔是什么）、${if (tagging) "用户打过的标签（各带几笔例子）、" else ""}最近几天已有的流水（#编号）。
来源各家给的信息不同：
- 建设银行「动账提醒」：有卡尾号、到分钟的时刻、支出/收入、金额，**没有商户**
- 招商银行：有卡尾号、金额、消费/退款，没有商户、没有时刻
- 掌上生活「交易提醒/退款提醒」：有商户（「支付宝-山姆会员商店」「财付通-xxx」前缀是支付渠道）、金额，没有卡尾号
- 支付宝「交易提醒」：有金额、是否花呗，没有商户

## 怎么整理
1. 每条原始通知都要有去处：记进某笔流水、放进 ignore（不是账）、或放进 unreadable（是账但读不出金额）。
2. **同一笔钱只记一笔**：招商银行 + 掌上生活几秒内报同一个金额是同一笔信用卡消费，合成一笔、raw_ids 都列上，
   商户取掌上生活的、卡尾号取招行的；同一条通知推了两遍（正文一字不差、时刻相近）也合成一笔。
   也要看「最近几天已有的流水」：新来的通知如果和已有的某笔是同一笔钱，别重复记。
3. 金额照原文抄。正文写着「敏感数据已隐藏」之类的读不出金额，放 unreadable，不要猜。
4. 不是账的放 ignore：营销、领券、立减金、活动、优惠券到期、还款提醒、空通知。
   **转账、红包、收款都是账，不放 ignore**：「向你转账」「收到转账」「转账到账」「已收款」「收款到账」「收到红包」，
   哪怕通知长得像聊天消息（微信里标题是人名、正文「[转账]…」）、没写商户；有金额就记，读不出金额放 unreadable。
5. 方向：消费、支出 → OUT；退款、退货 → REFUND（能在已有流水里找到原笔就填 refund_of）；
   原笔和退款在同一批里、还没有 # 编号时，填 refund_of_raw（原笔那条消费通知的 raw 编号）。
   例：raw#4「财付通-四川云慧充 3.00 消费」和 raw#8「财付通-四川云慧充 1.49 退货」→ 退款那笔 refund_of_raw 填 4。
   转入、结息、工资、别人转给用户的钱、收到的红包、收款 → IN（类别「转账红包」，工资、利息照旧）；
   信用卡还款、自己账户之间转、余额宝进出 → TRANSFER；转账给别人 → OUT（照样是花钱）。
   看不出是转进还是转出、是不是自己账户之间挪的，照样记一笔，方向填最像的，category 填 null，ask 里问清楚。
6. 类别：看商户、金额、时刻判断。商户记忆里有的直接用；关于用户记下的事里对得上的（哪张卡是谁在用、
   每月几号固定扣的那笔是什么）照着归。有把握（比如「山姆会员商店」→ 日用/超市日用）就归，ask 填 null；
   没把握（建行卡只有金额没有商户）category 填 null，ask 写到时候问用户的话：先一句问题，带上时刻和金额；
   再写你最像的 1–3 个猜测，每个带上要归的类别和依据（晚上对账时，问卡的选项就照你的猜测出）。比如
   「9月22日 19:18 建行卡支出 197.00，是什么？像晚饭（餐饮/堂食）：上周二这个时段也有一笔差不多的是堂食；也可能是买菜（餐饮/买菜）」。
   一点头绪都没有就只写问题。宁可问，不要乱归。
   二级只写买的是什么（外卖、打车、房租）；场合、为谁、哪件事、状态（约会、请客、给爸妈、报销）是标签的事，不建二级；
   时段、星期、哪张卡（早饭夜宵、周末工作日、8837 那张卡）从时刻和卡号就算得出来，二级、标签都不建。
${if (tagging) TAGGING_RULE else NO_TAGGING_RULE}
7. summary 写一句用户一眼能懂的话，12 字以内；没商户的写「建行卡一笔支出」这样的实话。
8. 一次调用 record_expenses 把这批全交上去。有被打回的，照原因改好只重交那几笔；实在弄不好就放 unreadable。
9. 全部交完后回一句话总结（几笔、几条忽略），不要调别的工具，不要编造没有的通知。
""".trimIndent()

    private const val NO_TAGGING_RULE =
        "   标签不归你打（用户自己打），一个都不打。"

    private const val TAGGING_RULE =
        "   标签（tags）只用「用户打过的标签」里已有的，照他打过的那几笔学：同一家、差不多的时段和金额、同一件事。\n" +
            "   有把握才打，拿不准就不打；不新建标签，也别为了标签去 ask。"

    /** 喂给整理员的一批：[text] 是原文，[counts] 只数个数（写 trace 用，不落内容） */
    class Input(val text: String, val counts: String)

    /**
     * 这一批的输入。会变的都在这里，system 保持不动。
     * [tags] 是用户打过的标签各带几笔例子，只在 [tagging] 开着时给
     */
    fun input(
        notes: List<RawNote>,
        categories: List<CategoryNode>,
        merchants: List<Pair<String, String>>,
        memories: List<Memory>,
        tagging: Boolean,
        tags: List<Pair<String, List<TxnBrief>>>,
        recent: List<TxnBrief>,
        zone: ZoneId
    ): Input {
        val text = buildString {
            append("这一批原始通知（${notes.size} 条）：\n")
            notes.forEach { append(LedgerText.raw(it, zone)).append('\n') }
            append("\n").append(LedgerText.categoryTree(categories)).append('\n')
            append("\n商户记忆（用户确认过的）：")
            append(if (merchants.isEmpty()) "还没有" else merchants.joinToString("；") { (m, c) -> "$m → $c" })
            append("\n\n关于用户记下的事（他说过的，认账时参考）：")
            if (memories.isEmpty()) append("还没有") else memories.forEach { append("\n- ").append(it.text) }
            if (tagging) {
                append("\n\n用户打过的标签（只用这些，照这几笔学）：")
                if (tags.isEmpty()) append("还没有，这次一个都不打")
                tags.forEach { (name, txns) ->
                    append("\n- ").append(name).append("：").append(txns.joinToString("；") { LedgerText.tagExample(it, zone) })
                }
            }
            append("\n\n最近几天已有的流水：")
            if (recent.isEmpty()) append("没有") else recent.forEach { append('\n').append(LedgerText.txn(it, zone)) }
        }
        return Input(
            text,
            "通知 ${notes.size} 条 · 类别 ${categories.size} 个 · 商户记忆 ${merchants.size} 条 · " +
                "记忆 ${memories.size} 条 · " + (if (tagging) "标签 ${tags.size} 个 · " else "") + "最近流水 ${recent.size} 笔"
        )
    }
}
