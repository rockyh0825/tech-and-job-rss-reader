# 動作確認(ローカル)

MVP のパイプライン(fetcher → Kafka → sink / live consumer → API / UI)が一通り動いていることをローカルで確認する手順。README の「クイックスタート」の 2 コマンド(`docker compose up` + `./gradlew bootRun`)を実行済みであることが前提。

なおアプリは正常時にはほとんどログを出さない(フィード取得失敗や publish 失敗時の WARN のみ)ため、動作確認はログではなく **kafka-ui・PostgreSQL・API** で行う。

## 0. 自動テスト

まず全テスト(単体テスト + EmbeddedKafka による結合テスト)が通ることを確認する。

```bash
./gradlew test
```

## 1. フィード巡回と Kafka への publish

fetcher は**起動の約 10 秒後に初回巡回**し、以降 15 分間隔で巡回する(`application.yml` の `rss-watch.fetch`)。起動から 1 分ほど待ってから確認するとよい。

- **kafka-ui で確認**: <http://localhost:8081> を開き、topic `rss.items` の Messages にメッセージが入っていることを確認する。key がフィード名になっており、同じフィードのアイテムが同じパーティションに載っていることも観察できる(3 パーティション)
- **CLI で確認する場合**:

```bash
docker exec rss-watch-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:29092 --topic rss.items \
  --from-beginning --max-messages 3 --property print.key=true
```

## 2. PostgreSQL への保存(sink consumer)

sink consumer が Kafka から読んだアイテムを PostgreSQL に書き込んでいることを確認する。

```bash
# tech / jobs の両カテゴリでアイテムが保存されていること
docker exec rss-watch-postgres psql -U rsswatch -d rsswatch \
  -c "SELECT category, COUNT(*) FROM items GROUP BY category;"

# 技術キーワードが辞書ベースで抽出されていること
docker exec rss-watch-postgres psql -U rsswatch -d rsswatch \
  -c "SELECT keyword, COUNT(*) AS c FROM item_keywords GROUP BY keyword ORDER BY c DESC LIMIT 10;"
```

## 3. クロスリンクレポート API

目玉機能のクロスリンク(求人で言及されている技術 × その技術の記事)が返ってくることを確認する。

```bash
# 正常系: crossSections に技術キーワードごとの言及回数と記事が並ぶ
curl -s "http://localhost:8080/api/report?days=7" | python3 -m json.tool | head -40

# 異常系: days は 1〜365。範囲外は 400 Bad Request
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/api/report?days=0"
```

## 4. ブラウザ UI とリアルタイム新着(SSE)

- <http://localhost:8080> を開き、レポート(技術ランキングと記事のクロスリンク)が表示されることを確認する
- リアルタイム新着は SSE(`GET /api/stream`)で配信される。curl で直接確認する場合:

```bash
curl -N http://localhost:8080/api/stream
```

接続直後に `:connected` が届き、以降は新着があるたびに `event:item` + `data:{...}` が流れてくる。**手っ取り早く新着を見たい場合は、アプリを再起動して起動直後に接続する**とよい(約 10 秒後の初回巡回で全フィード分のアイテムがまとめて流れてくる)。定常運用では次の巡回(最大 15 分後)を待つ。

## 5. 冪等性と consumer group の独立オフセット

アプリを Ctrl-C で止めて `./gradlew bootRun` で再起動すると、fetcher が同じアイテムを再度 Kafka へ publish するが、**DB には重複保存されない**(guid を主キーとした冪等書き込み)。

```bash
# 再起動の前後で件数を比較する(純粋な新着分しか増えない)
docker exec rss-watch-postgres psql -U rsswatch -d rsswatch -c "SELECT COUNT(*) FROM items;"
```

同じ topic を sink / live の 2 つの consumer group が独立オフセットで読んでいることも確認できる。

```bash
docker exec rss-watch-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:29092 --describe --group sink
# --group live に変えると live consumer 側のオフセットを確認できる
```

各パーティションの `LAG` が 0(= 溜まりなく消費できている)であることを確認する。

## 6. PostgreSQL の停止・再開に対する回復(sink の取りこぼしなし)

PostgreSQL が一時停止しても、sink は書き込み失敗でオフセットをコミットしないため、復旧後に再配信され冪等書き込みで重複なく回復する。

```bash
# 停止中も fetcher → Kafka は動き続ける(report API は 500 になる)
docker stop rss-watch-postgres

# 再開すると sink が溜まった分を catch-up し、件数が回復する
docker start rss-watch-postgres
```

## 7. 片付け・やり直し

```bash
# アプリは Ctrl-C で停止し、Kafka と PostgreSQL を止める
docker compose -f docker/docker-compose.yml down

# まっさらな状態からやり直す場合(Kafka と PostgreSQL のデータも消す)
docker compose -f docker/docker-compose.yml down -v
```
