# 配送草案固定样例预览

在 `delivery/`、Java 21 环境中执行：

```powershell
./mvnw.cmd '-Dtest=DraftGeneratorTests,MixedDestinationDraftTests' test
java -cp 'target/classes;target/test-classes' org.example.delivery.planning.DraftPreview
```

用浏览器打开命令输出的 `target/planning-0001-preview.html`。先核对小陈在岗和请假的两组东区运单，再看同批混合去向的片区分组和排除明细。页面直接使用与应用服务测试相同的固定样例和规则。生成和查看只需本地 Java 与浏览器。
