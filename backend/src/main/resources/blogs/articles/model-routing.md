你已经接入了一个大模型接口，页面也能正常聊天。第二天服务商限流，所有请求都失败；你临时把 URL 和模型名改成另一家，结果请求参数、错误格式和返回字段又不一样。很多初学者会把大量 `if/else` 写进 Controller，最终每增加一个模型都要改页面和业务代码。本文会把一次模型调用拆成稳定接口、厂商适配器和路由策略，用 Spring Boot 实现一个最小的多模型路由，并说明超时、回退、配置和排错应该放在哪里。

## 1. 从一个实际问题开始

同样是“生成一句摘要”，A 服务使用 `model` 和 `messages`，B 服务可能还需要独立的地址和密钥。若 Controller 直接认识所有厂商，业务逻辑会和网络细节绑定：切换模型时容易漏改参数，密钥也可能被错误传到浏览器。

多模型路由要解决的核心不是下拉框，而是让业务只表达“我要一次聊天”，由后端决定把请求交给谁。

## 2. 先建立整体认识

```text
页面选择模型
→ Controller 接收稳定请求
→ Router 校验模型并选择 Provider
→ Provider 调用厂商 API
→ 统一结果或统一错误
→ 页面展示
```

Router 像前台分诊，Provider 像不同科室。分诊只决定去哪里，不负责替科室完成工作。这个类比的局限是，模型服务还存在计费、速率限制和数据合规差异，所以不能只按“谁能用”来选择。

## 3. 必须掌握的核心概念

### 3.1 稳定接口

业务层只依赖自己的 `ChatProvider`，不依赖某个厂商 SDK。接口要表达业务真正需要的输入和输出，避免把厂商特有字段扩散到所有层。

### 3.2 适配器

每个 Provider 把统一请求转换成厂商格式，再把返回值转换回来。配置错误、HTTP 错误和响应解析错误也在这里变成统一异常。

### 3.3 路由与回退

路由可以按用户选择、任务类型、价格或健康状态决定 Provider。自动回退只适合允许重试的请求，并且要记录实际使用了哪个模型。图片生成等非确定任务回退后结果会变化，不能假装仍由原模型完成。

### 3.4 密钥边界

API Key 只放后端环境变量。前端提交的是模型 ID，而不是地址和密钥。后端必须用允许列表校验模型 ID，防止请求者借接口调用任意模型。

## 4. 一个最小可运行示例

下面代码使用 Spring Boot 的 `RestClient` 调用 OpenAI 兼容的聊天接口。两个服务商只需配置不同地址、密钥和模型名。

```java
package demo;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

interface ChatProvider {
    String id();
    String chat(String message);
}

@Service
class OpenAiCompatibleProvider implements ChatProvider {
    private final String id;
    private final String model;
    private final RestClient client;

    OpenAiCompatibleProvider(
        @Value("${demo.provider.id}") String id,
        @Value("${demo.provider.base-url}") String baseUrl,
        @Value("${demo.provider.api-key}") String apiKey,
        @Value("${demo.provider.model}") String model
    ) {
        this.id = id;
        this.model = model;
        this.client = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    }

    public String id() { return id; }

    @SuppressWarnings("unchecked")
    public String chat(String message) {
        Map<String, Object> body = Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "user", "content", message))
        );
        Map<String, Object> response = client.post()
            .uri("/chat/completions")
            .body(body)
            .retrieve()
            .body(Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        Map<String, Object> first = choices.getFirst();
        Map<String, Object> answer = (Map<String, Object>) first.get("message");
        return String.valueOf(answer.get("content"));
    }
}

@Service
class ModelRouter {
    private final Map<String, ChatProvider> providers;

    ModelRouter(List<ChatProvider> providers) {
        this.providers = providers.stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(ChatProvider::id, p -> p)
        );
    }

    String chat(String providerId, String message) {
        ChatProvider provider = providers.get(providerId);
        if (provider == null) throw new IllegalArgumentException("unsupported model: " + providerId);
        return provider.chat(message);
    }
}
```

对应配置只引用环境变量：

```yaml
demo:
  provider:
    id: deepseek
    base-url: ${MODEL_BASE_URL}
    api-key: ${MODEL_API_KEY}
    model: ${MODEL_NAME}
```

请求先进入 `ModelRouter`，模型 ID 在 Map 中命中后才会交给 Provider。真正发生网络调用的是 `RestClient.post()`；返回数据在 Provider 内被解析为普通字符串，业务层不需要认识 `choices` 等厂商字段。

## 5. 逐步完成实际操作

1. 在 Spring Boot 项目中加入 Web 依赖并创建上述类。启动时能创建 Bean，说明配置字段完整。
2. 在系统环境中设置 `MODEL_BASE_URL`、`MODEL_API_KEY` 和 `MODEL_NAME`，不要提交 `.env`。
3. 给 Router 增加一个 Controller，只接收 `providerId` 和 `message`。返回结果中附带实际 Provider ID，便于确认路由。
4. 接入第二家服务时新增一个实现或配置化 Provider，不修改页面业务协议。
5. 为每条线路设置连接超时、读取超时、并发上限和独立监控。

## 6. 验证是否成功

先使用一个短问题验证主线路，再故意填错模型名，确认页面收到可读错误且日志包含 Provider ID。随后模拟主线路超时，若配置了回退，应在日志中明确记录“主线路失败、备用线路成功”，而不是静默切换。

## 7. 常见问题与排错

- **401/403**：检查密钥是否属于当前地址，环境变量是否真的加载；不要打印完整密钥。
- **404**：确认 `base-url` 是否已经包含 `/v1`，避免重复或遗漏路径。
- **模型不存在**：先调用服务商模型列表或核对控制台，不要凭名称猜测。
- **页面一直等待**：为 HTTP 客户端设置读取超时，并把上游错误转换为 502 等明确状态。
- **切换后回答仍来自旧模型**：检查前端提交的 ID、Router 映射和缓存键是否包含模型 ID。
- **回退造成重复扣费**：仅在能够判断请求未成功或业务允许重复生成时回退，并记录调用 ID。

多模型能力的重点是稳定边界。页面面对一个协议，业务依赖一个接口，厂商差异留在适配器，路由和回退才能被单独测试。
