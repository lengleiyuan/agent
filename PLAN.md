# agent  框架编写

> harnessAgent框架，逐函数、逐类、逐设计决策认真编写每一行代码并中文注释。

---
## 模块职责
### agent-bootstrap 用于框架启动
### agent-core 用于agent等的核心代码
### agent-harness 用于对外提供的sdk包，门面

## 框架严格参照 agentscope 地址 https://github.com/agentscope-ai/agentscope-java
本项目不需要agentscope那么多能力，但是设计需要严格参考，文件命名可以更有范一些，要避免和agentscope相同命名
比如 AgentBase 可以命名为AbstractAgent,项目依然使用 Reactor 响应式编程
完整的hook体系，tool体系，像基础的工具hook都需要，但像mcp和skill这些需要预留位置（可暂不需要），直接采用harnessAgent，多租户权限体系，而不是在ReActAgent上面在包一层
支持单次对话（chat）和多轮对话（session）持久化，以及运行时上下文体系
要支持总多服务商，比如openAi，deepseek，dashScope,以及自定义的服务商
项目json序列化要采用com.alibaba.fastjson2
最后本地要求可以启动，要有一个测试页面进行测试

如上这些简要要求，有其他问题，你可以问我，我进行补充。一定要先详细方案设计，要把结构设计清晰在动手

