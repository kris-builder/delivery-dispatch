# 配送草案固定样例预览

在 `delivery/`、Java 21 环境中执行：

```powershell
./mvnw.cmd '-Dtest=DraftGeneratorTests' test
java -cp 'target/classes;target/test-classes' org.example.delivery.planning.DraftPreview
```

用浏览器打开命令输出的 `target/planning-0001-preview.html`，展开两组运单核对。页面直接使用与应用服务测试相同的固定样例和规则。生成和查看只需本地 Java 与浏览器。
