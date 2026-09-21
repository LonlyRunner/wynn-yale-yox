你把几十份 PDF 导入向量库，提问“退款期限是多少天”，系统却引用了一段产品介绍；换一个问法又完全正确。模型没有报错，向量库也返回了结果，但“返回结果”不等于“找到了能回答问题的证据”。问题可能出在扫描件没有 OCR、分块切断了标题与正文、检索只看语义却忽略精确编号，或低相关片段仍被塞进 Prompt。本文从一次答非所问开始，拆解 RAG 的入库、检索、生成和引用链路，并用一个纯 Python 最小示例验证“检索证据后再回答”的基本机制。

## 1. 从一个实际问题开始

文档中原句是“签收后七日内可申请无理由退货”，但分块时“退款规则”标题留在上一块，正文又与售后地址混在一起。用户问“退款期限”，向量检索可能把“退款”和另一段财务退款流程判为更相似。

RAG 的错误通常不是一个参数造成的。解析质量、分块、召回、排序和生成任何一层都可能让证据偏离问题。

## 2. 先建立整体认识

```text
文档上传
→ 解析/OCR/清洗
→ 按结构分块
→ 建立关键词与向量索引
→ 用户问题改写
→ 混合召回
→ 去重、重排和阈值过滤
→ 证据拼入 Prompt
→ 模型回答并标注引用
```

RAG 是“先找资料，再让模型依据资料回答”。它不负责训练模型，也不能自动保证资料正确。可以类比开卷考试，但真实系统还要处理权限：考生不能看到不属于自己的试卷。

## 3. 必须掌握的核心概念

### 3.1 解析决定索引里有什么

文本型 PDF 可以直接抽取文字，扫描型 PDF 需要 OCR。表格要保留表头与行列关系，图片说明要记录来源页码。若解析阶段已经丢字，后面的模型无法把信息找回来。

### 3.2 分块是在完整语义和定位精度之间取舍

块太小会丢掉上下文，太大则把多个主题混在一起并浪费上下文窗口。可以从 400～800 个中文字符、10%～20% 重叠开始，再用评测数据调整。标题、章节路径、页码、文档版本和权限应作为 metadata 与文本一起保存。

### 3.3 Embedding 与关键词检索各有所长

Embedding 把文本映射成向量，适合语义相近的表达；BM25 等关键词方法更擅长型号、编号和专有名词。混合检索先从两路取候选，再融合排名，通常比只依赖一路稳定。

### 3.4 重排与阈值控制最终证据

Reranker 重新判断问题与候选片段的相关性。若最高分仍低于阈值，应拒答或请用户补充，而不是把无关文本交给模型自由发挥。

### 3.5 权限必须在检索时过滤

租户 ID、知识库 ID 和文档权限应进入数据库或向量库过滤条件。先召回后过滤可能让无权内容进入日志、缓存或模型上下文。

## 4. 一个最小可运行示例

为了让流程可以直接运行，下面用词频余弦相似度代替真正的 Embedding。它的效果不能代表生产向量检索，但能清楚展示“分块 → 检索 → 引用”的数据流。

```python
import math
import re
from collections import Counter

documents = [
    {"id": "refund-1", "source": "售后规则.md#退款", "text": "商品签收后七日内可申请无理由退货。"},
    {"id": "invoice-1", "source": "发票规则.md#开票", "text": "订单完成后可在个人中心申请电子发票。"},
    {"id": "delivery-1", "source": "配送规则.md#时效", "text": "普通地区预计三至五日送达。"},
]

def tokens(text):
    return re.findall(r"[\u4e00-\u9fff]|[a-z0-9]+", text.lower())

def cosine(left, right):
    a, b = Counter(tokens(left)), Counter(tokens(right))
    dot = sum(value * b[word] for word, value in a.items())
    norm_a = math.sqrt(sum(value * value for value in a.values()))
    norm_b = math.sqrt(sum(value * value for value in b.values()))
    return dot / (norm_a * norm_b) if norm_a and norm_b else 0.0

def retrieve(question, top_k=2):
    ranked = sorted(
        ((cosine(question, item["text"]), item) for item in documents),
        key=lambda pair: pair[0],
        reverse=True,
    )
    return [(score, item) for score, item in ranked[:top_k] if score > 0]

question = "退货期限是几天"
hits = retrieve(question)
if not hits:
    print("没有找到足够证据")
else:
    for index, (score, item) in enumerate(hits, 1):
        print(f"[{index}] score={score:.3f} source={item['source']} text={item['text']}")
```

问题进入 `retrieve` 后被切成 token；每个片段计算相似度并排序；阈值过滤掉完全无关结果；最终输出文本和来源。真实系统会把这些片段编号后交给模型，并要求关键结论使用 `[1]` 这样的编号引用。

## 5. 逐步完成实际操作

1. 保存为 `rag_demo.py` 并运行：

```bash
python rag_demo.py
```

应看到退款片段排在第一位，并带有来源。

2. 把文档按标题切成小段，每段保存稳定 ID、来源、页码和权限字段。
3. 用真实 Embedding 服务批量生成向量，同时建立关键词索引。
4. 查询时在权限条件内分别召回，再融合、去重和重排。
5. 拼接 Prompt 时保留片段编号；没有足够证据就明确拒答。
6. 文档更新时建立新版本索引，验证后切换 active version，再清理旧版本。

## 6. 如何验证

准备一组带标准答案和标准来源的问题。检索阶段查看 Recall@K、MRR 或 Hit Rate；生成阶段检查事实一致性、答案相关性、引用正确性和拒答质量。若标准片段根本没被召回，应修解析、分块或检索；若证据正确但回答错误，再调整 Prompt 或模型。

## 7. 常见问题与排错

- **扫描 PDF 检索不到**：确认是否执行 OCR，并抽查乱码、页码和表格。
- **换个问法就找不到**：增加查询改写和向量召回，同时保留关键词检索。
- **总引用同一大段**：检查分块是否过大、标题是否进入片段。
- **答案很流畅但没有依据**：设置相关性阈值，Prompt 明确限制依据，并检查引用是否真的支持结论。
- **不同用户看到同一私有资料**：权限必须进入检索过滤和缓存键。
- **更换 Embedding 模型后效果异常**：新旧向量空间不可混用，需要全量重算并记录模型版本。

RAG 的质量来自一条可测量的证据链。先确认文档被正确解析，再确认相关片段被召回，最后才评价模型的回答。
