package org.example.delivery.planning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class DraftPreview {
    private DraftPreview() {}

    public static void main(String[] args) throws IOException {
        Path output = Path.of("target", "planning-0001-preview.html");
        Files.writeString(output, render());
        System.out.println(output.toAbsolutePath());
    }

    private static String render() {
        var start = DraftPreviewFixture.START;
        var dateTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT)
                .withZone(DraftPreviewFixture.BEIJING);
        var time = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
                .withZone(DraftPreviewFixture.BEIJING);
        var onDuty = DraftPreviewFixture.generate(false, start);
        var onLeave = DraftPreviewFixture.generate(true, start);

        return """
                <!doctype html>
                <html lang="zh-CN">
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>江岸网点 · 配送草案预览</title>
                <style>
                  body { margin: 0; background: #f2f5f8; color: #162235; font: 16px/1.6 system-ui, sans-serif; }
                  main { max-width: 980px; margin: auto; padding: 32px 20px; }
                  h1 { margin: 0 0 8px; }
                  .context { color: #49596e; margin: 0 0 24px; }
                  .scenarios { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%%, 340px), 1fr)); gap: 16px; }
                  section { background: white; border: 1px solid #ccd6e0; border-radius: 12px; padding: 20px; }
                  section h2 { margin: 0; }
                  dl { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 16px 0; }
                  dl div { padding: 8px; background: #eef4fa; border-radius: 6px; }
                  dd { margin: 0; font-size: 26px; font-weight: 700; }
                  summary { cursor: pointer; color: #174d86; }
                  table { width: 100%%; border-collapse: collapse; margin-top: 8px; }
                  th, td { text-align: left; padding: 6px 4px; border-bottom: 1px solid #e3e9ee; }
                  td:first-child { font-variant-numeric: tabular-nums; }
                </style>
                <main>
                  <h1>%s · %s配送草案预览</h1>
                  <p class="context">北京时间 %s–%s 上午班 · 同一批已实收、仍在站且已确认上门的样例件</p>
                  <div class="scenarios">
                %s
                %s
                  </div>
                </main>
                </html>
                """.formatted(onDuty.networkName(), onDuty.areaName(), dateTime.format(start),
                        time.format(DraftPreviewFixture.END),
                        scenario("小陈在岗", "按东区常规责任生成建议。", onDuty),
                        scenario("小陈请假", "小李同班在岗，具体票件等待调度手工安排。", onLeave));
    }

    private static String scenario(String heading, String context, DraftGenerator.Draft draft) {
        var rows = new StringBuilder();
        for (var item : draft.items()) {
            String worker = item.proposedWorker() == null ? "—" : item.proposedWorker().displayName();
            String status = item.pendingReason() == null ? "已拟派" : "责任人缺岗，待安排";
            rows.append("<tr><td>%s</td><td>%s</td><td>%s</td></tr>\n".formatted(item.waybillNo(), worker, status));
        }
        return """
                <section>
                  <h2>%s</h2>
                  <p>%s</p>
                  <dl>
                    <div><dt>总数</dt><dd>%d</dd></div>
                    <div><dt>已拟派</dt><dd>%d</dd></div>
                    <div><dt>待安排</dt><dd>%d</dd></div>
                  </dl>
                  <details>
                    <summary>查看 %d 票运单</summary>
                    <table>
                      <thead><tr><th scope="col">运单号</th><th scope="col">拟领件人</th><th scope="col">草案状态</th></tr></thead>
                      <tbody>%s</tbody>
                    </table>
                  </details>
                </section>
                """.formatted(heading, context, draft.total(), draft.assigned(), draft.pending(), draft.total(), rows);
    }
}
