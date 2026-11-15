# Лабораторная работа №2. Многопоточное умножение матриц (Java)

Многопоточное умножение матриц на Java с поддержкой генерации данных через IPC,
статического и динамического распределения нагрузки, а также возведения матрицы
в степень с барьерной синхронизацией.

## Среда выполнения и требования

- **Язык программирования:** Java (OpenJDK 27, компиляция с `--release 8`)
- **Операционная система:** Windows 11 Домашняя
- **Процессор:** AMD Ryzen 5 5560U (6 физических ядер / 12 логических, SMT, L3 = 8 МБ)
- **Оперативная память:** 16 ГБ DDR4
- **Сторонние библиотеки:** только JUnit 5 (`junit-platform-console-standalone-1.11.3.jar` в `lib/`) и matplotlib для графиков
Замеры проводились **на основной ОС** ноутбука (не в виртуальной машине, не от батареи).


## Структура репозитория

| Файл | Назначение |
|------|-----------|
| `src/MatrixMath.java` | Все алгоритмы: умножение (static/dynamic, два порядка циклов), треугольная A, возведение в степень с барьером, генерация, контрольные суммы. Программы и тесты вызывают именно этот код |
| `src/ProgressPrinter.java` | Поток вывода прогресса (раз в 200 мс, только при изменении процента) |
| `src/MatrixIO.java` | Двоичный протокол передачи матриц и запуск процесса-генератора |
| `src/MatrixMul.java` | Задание на «3»: умножение матриц в несколько потоков |
| `src/MatrixGenerator.java` | Задание на «4»: процесс-генератор (пишет матрицы в stdout) |
| `src/MatrixMulIPC.java` | Задание на «4»: процесс-вычислитель (static/dynamic, треугольная A) |
| `src/MatrixMulPower.java` | Задание на «5»: Aᵖ с барьером / без барьера / с пересозданием потоков |
| `src/Benchmark.java` | Автоматические замеры (5 замеров, без min и max, среднее) → CSV |
| `scripts/plot.py` | Построение графиков из CSV (matplotlib) |
| `test/MatrixMathTest.java` | Юнит-тесты, JUnit 5 (29 тестов) |
| `results/*.csv` | Сырые замеры (столбцы `run1…run5`, `mean_s`, `speedup`, `efficiency`) |
| `results/*.png` | Графики |
| `OS_Laba_2_otchet.docx` | Полный отчёт по лабораторной работе |

## Инструкция по сборке и запуску

### 1. Клонирование репозитория

```powershell
git clone https://github.com/safinapetrova/OS_Laba2.git
cd OS_Laba2
```
### 2. Сборка

```powershell
javac --release 8 -d out src/*.java
```
Примечание: флаг -encoding UTF-8 не требуется. Начиная с JDK 18 (JEP 400) кодировка файлов по умолчанию — UTF-8.

### 3. Запуск программ

Аргументы: `<n> <m> <k> <потоки> [порядок/режим]`

```powershell
# «3» — умножение A(n×m) · B(m×k) в несколько потоков
java -Xmx4g -cp out MatrixMul 3000 3000 3000 8
java -Xmx4g -cp out MatrixMul 2000 2000 2000 1 ijk   # «школьный» порядок циклов для сравнения

# «4» — генерация в отдельном процессе + треугольная матрица
java -Xmx4g -cp out MatrixMulIPC 2000 2000 2000 8 static triangular
java -Xmx4g -cp out MatrixMulIPC 2000 2000 2000 8 dynamic triangular

# «5» — возведение A(n×n) в степень p с барьером
java -Xmx4g -cp out MatrixMulPower 1000 20 8          # с барьером
java -Xmx4g -cp out MatrixMulPower 1000 20 8 nobarrier # барьер убран (демонстрация гонки)
java -Xmx4g -cp out MatrixMulPower 1000 20 8 respawn   # потоки заново на каждом шаге
```
### 4. Автоматические замеры и графики

```powershell
java -Xmx4g -cp out Benchmark mul   500,1500,3000 threads=1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,20,24,32,64,128 runs=5 out=results
java -Xmx4g -cp out Benchmark order 2000 runs=3 out=results
java -Xmx4g -cp out Benchmark tri   1500,3000 threads=1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,20,24,32,64,128 runs=3 out=results
java -Xmx4g -cp out Benchmark ipc   500,1500,3000 out=results
java -Xmx4g -cp out Benchmark pow   1000 20 threads=1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,20,24,32,64,128 runs=3 out=results

python scripts/plot.py --results results --physical 6 --logical 12
```
### 5. Тесты

```powershell
javac --release 8 -d out src/*.java
javac -cp "out;lib/junit-platform-console-standalone-1.11.3.jar" -d out-test test/MatrixMathTest.java
java -jar lib/junit-platform-console-standalone-1.11.3.jar -cp "out;out-test" --select-class MatrixMathTest
```
Все 29 тестов проходят. Многопоточные тесты повторяются по 100 раз: ошибки синхронизации проявляются не при каждом запуске. `@Timeout(60)` не даст тесту зависнуть навсегда, если барьер сломается.

### Пример запуска

Матрица 3000×3000, 10 потоков, динамическое распределение:

```powershell
java -Xmx4g -cp out MatrixMul 3000 3000 3000 10
```
Ожидаемое время: ≈ 1.74 секунды (в один поток — ≈ 11.96 секунды, ускорение ≈ 6.9×).
