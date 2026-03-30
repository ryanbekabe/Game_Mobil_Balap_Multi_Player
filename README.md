# Game Mobil Balap Multi Player 🏎️💨

https://github.com/ryanbekabe/Game_Mobil_Balap_Multi_Player

Game Mobil Balap Multi Player adalah sebuah game balap mobil bergaya "Top-Down Racer & Battle Royale" yang dibangun menggunakan bahasa Kotlin untuk platform Android. Proyek ini mendemonstrasikan bagaimana membangun sebuah game multiplayer lokal real-time melalui jaringan UDP (LAN/WiFi) secara mandiri dari nol (tanpa third-party game engine seperti Unity atau Unreal).

## Fitur Utama ✨
- 📱 **Splash Screen Modern**: Sambutan visual yang menarik dengan animasi fade-in dan loading bar saat memulai game.
- 🎨 **UI/UX Profesional**: Antarmuka Menu Utama yang didesain ulang dengan tema *Dark Mode*, kontainer kartu modern, dan palet warna neon yang futuristik.
- 🎮 **Multiplayer Lokal (UDP Network)**: Bermain bersama hingga 4 orang dalam satu jaringan WiFi yang sama! Host secara otomatis menyiarkan ruang permainannya menggunakan arsitektur port dinamis.
- 🕹️ **Kontrol Presisi**: Antarmuka kontrol dalam-game yang ditingkatkan dengan ikon transparan (Overlay) yang intuitif untuk kemudi dan akselerasi.
- 🤖 **Bot AI Cerdas**: Jika tidak ada teman bermain, Anda dapat melawan bot yang menyetir mobil secara otomatis mencari koin, dengan 3 mode kesulitan (Lambat, Sedang, Cepat).
- 🏆 **Sistem Battle Royale Koin**: Bukan sebatas adu cepat mencapai pintu keluar! Pemenang adalah dia yang berhasil mengumpulkan Koin terbanyak dari arena saat batas portal terakhir telah terpenuhi. 
- ⚕️ **Sistem Health (HP) & Tabrakan Fisik**: Awas tembok! Jika Anda menabrak dinding maze (atau pemain bertabrakan), kecepatan akan terpantul, darah (Health) Anda berkurang, dan koin yang dikumpulkan akan bertebaran jatuh ke aspal.
- 📦 **Kotak Harta & Kekacauan (Power-ups)**: Segarkan kembali darah Anda dengan memungut item *Health*, atau lompat ruang secara instan meninggalkan musuh menggunakan item *Teleportasi Kuantum*!
- 🗺️ **Labirin Acak & Zona Liar (Procedural Maze)**: Nikmati area balapan yang selalu acak dan menantang (ada Zona Es yang super licin, serta Zona Lumpur yang sangat memperlambat aksi).

## Instalasi (Clone & Build)
Jika Anda menggunakan IDE Android Studio, ikuti langkah berikut:
1. Salin repositori ini: `git clone https://github.com/ryanbekabe/Game_Mobil_Balap_Multi_Player.git`
2. Buka *Android Studio* > **Open**, kemudian arahkan ke folder tempat Anda melakukan clone.
3. Sinkronisasikan Gradle (jika diminta).
4. Klik tombol **Run 'app'** (Segitiga hijau) untuk melakukan instalasi *Build Debug* ke perangkat Android / Emulator Anda.

## Cara Bermain (Sebagai Host & Client) 🕹️
1. Buka Game di satu perangkat Android dan tunggu **Splash Screen** selesai.
2. Di Menu Utama, masukkan nama Anda. Jika bertindak sebagai **HOST GAME**, Anda dapat mengatur jumlah maupun kepintaran Bot AI.
3. Jika ingin bergabung, pastikan perangkat terhubung di WiFi yang sama, biarkan Host IP dikosongkan (Mode otomatis) dan tekan **JOIN GAME**. 
4. *Gas* menggunakan tombol kontrol baru di layar dan kumpulkan Koin sebanyak mungkin!

## Lisensi 📄
Proyek ini dilisensikan di bawah spesifikasi [MIT License](LICENSE).
Bebas digunakan, dimodifikasi, maupun didistribusikan untuk tujuan belajar maupun kustomisasi bagi siapa saja secara opensource, dengan tetap menyertakan atribusi / lisensi aslinya.
