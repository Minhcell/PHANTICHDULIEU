# Phân tích Cuộc gọi / SMS — bản PC (exe) và Android (apk), chạy offline và mạng LAN

## Đưa lên GitHub (1 repo riêng)
Gốc repo chỉ cần:
```
.github/workflows/build.yml
web/index.html
web/xlsx.full.min.js
```
Commit lên nhánh `main` → tab **Actions** tự build → mục **Artifacts** tải
`PhanTichCuocGoi-exe` và `PhanTichCuocGoi-apk`.

## Lần đầu chạy
Chọn thư mục lưu dữ liệu (mặc định `PhanTich_DuLieu` cạnh file exe) rồi bấm **OK**,
hoặc nhập địa chỉ máy chủ LAN để dùng chung dữ liệu.
Trong thư mục dữ liệu:
- `kho.json` — tài khoản người dùng, mật khẩu admin
- `TepDaPhanTich\mobi|vina|viettel\` — bản sao các file đã phân tích
- `KetQua\` — file Excel kết quả xuất ra

Chuyển máy: copy `PhanTichCuocGoi.exe` và thư mục `PhanTich_DuLieu` vào cùng một chỗ.

## Mạng LAN
- Máy chủ (exe): **Mạng LAN** → tích **Cho máy khác trong mạng LAN dùng chung** → đặt mật khẩu → **Lưu cài đặt**.
  Cổng mặc định **6868**. Windows hỏi tường lửa: tích cả **Private** và **Public** → **Allow access**.
- Máy khác (exe/apk): **Mạng LAN** → nhập địa chỉ máy chủ (VD `10.71.5.20`) và mật khẩu → **Kết nối**.
- Không cài app: mở trình duyệt gõ `http://10.71.5.20:6868`.
- Dùng chung: tài khoản, mật khẩu admin, các tệp đã phân tích (ô **Hoặc tệp đã lưu**), file kết quả.
