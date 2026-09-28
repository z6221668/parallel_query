# 贡献指南

感谢参与改进 Parallel Query Starter。提交问题时，请提供 JDK、Spring Boot 版本、最小复现代码、期望行为和实际行为；不要在公开 Issue 中粘贴密钥或真实业务数据。

提交代码前，请使用 JDK 21 执行 `mvn verify`。修改代理、并发、超时或上下文传递行为时，请添加能重现问题的测试，并同步更新 README 中对应的使用边界。

Pull Request 请说明行为变化、验证命令与结果，以及对现有应用的兼容性影响。安全漏洞请按照 [SECURITY.md](SECURITY.md) 私下报告。
