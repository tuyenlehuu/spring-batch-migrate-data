# Transaction History Migrator (Spring Batch)

Project này migrate dữ liệu từ bảng `transaction_history` (service B/core) sang API upsert của service A.

## Luồng migrate

1. Manager step chia dữ liệu theo range `id` (partition).
2. Worker step đọc theo trang trong từng partition, sort tăng dần theo `id`.
3. Chunk size mặc định `500`.
4. Writer gọi API `POST /api/fin-txn/upsert` với payload dạng list.
5. Khi lỗi, step retry tối đa `3` lần/chunk.

## Cấu hình chính

Sửa trong `src/main/resources/application.yml`:

- `app.datasource.source.*`: kết nối DB nguồn chứa `transaction_history`
- `migration.source-table`: tên bảng nguồn
- `migration.service-a-upsert-url`: endpoint upsert của service A
- `migration.chunk-size`, `page-size`, `fetch-size`
- `migration.grid-size`: số partition
- `migration.thread-pool-size`: số luồng xử lý song song

## Chạy

```bash
mvn spring-boot:run
```

Hoặc build jar:

```bash
mvn clean package
java -jar target/txn-history-migrator-1.0.0.jar
```

## Estimate thời gian (6 triệu record, 500/chunk, 5s/chunk)

- Tổng batch = `12,000`
- Thời gian tuần tự (1 luồng) = `12,000 * 5s = 60,000s` = **16 giờ 40 phút**
- Thời gian lý thuyết khi chạy song song `N` luồng = `16 giờ 40 phút / N`
  - 4 luồng: ~`4 giờ 10 phút`
  - 8 luồng: ~`2 giờ 05 phút`
  - 12 luồng: ~`1 giờ 23 phút`

Thực tế thường thấp hơn lý thuyết do bottleneck API/DB/network, nên thường đạt khoảng 60-80% hiệu suất song song.
