import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

public class ZipToGzipConverter {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Использование: java ZipToGzipConverter <zip_path> <gzip_path>");
            System.exit(1);
        }
        try {
            convertSingleFileZipToGzip(args[0], args[1]);
        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
        }
    }

    public static void convertSingleFileZipToGzip(String zipPath, String gzipPath) throws IOException {
        if (!new File(zipPath).exists()) throw new IOException("Файл '" + zipPath + "' не найден");
        byte[] zipData = Files.readAllBytes(Paths.get(zipPath));

        if (zipData.length < 30 || read32(zipData, 0) != 0x04034B50) {
            throw new IOException("Файл не является корректным ZIP-архивом");
        }

        int flags = read16(zipData, 6);
        if ((read16(zipData, 4) > 20)) throw new IOException("ZIP версия выше обычного DEFLATE");
        if ((flags & 0x01) != 0) throw new IOException("ZIP использует шифрование");
        if ((flags & 0x08) != 0) throw new IllegalArgumentException("ZIP использует Data Descriptor");
        if (read16(zipData, 8) != 8) throw new IOException("Метод сжатия не поддерживается (ожидается DEFLATE)");

        long localCrc = read32(zipData, 14);
        long localCompSize = read32(zipData, 18);
        long localUncompSize = read32(zipData, 22);
        int filenameLen = read16(zipData, 26);
        int extraLen = read16(zipData, 28);

        // Проверка границ локального заголовка
        if (30L + filenameLen + extraLen > zipData.length) {
            throw new IOException("Локальный заголовок ZIP выходит за границы данных");
        }

        long startCompressedData = 30L + filenameLen + extraLen;
        if (startCompressedData + localCompSize > zipData.length) {
            throw new IOException("Размер данных выходит за фактические границы ZIP-файла");
        }

        byte[] filename = Arrays.copyOfRange(zipData, 30, 30 + filenameLen);
        if (filename.length > 0 && filename[filename.length - 1] == '/') {
            throw new IOException("ZIP содержит директорию вместо файла");
        }

        validateCD(zipData, localCrc, localCompSize, localUncompSize, filename);

        try (FileOutputStream fos = new FileOutputStream(gzipPath)) {
            fos.write(new byte[]{0x1F, (byte)0x8B, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03});                 
            fos.write(zipData, (int) startCompressedData, (int) localCompSize); 
            
            byte[] footer = new byte[8];
            write32(footer, 0, (int) localCrc);
            write32(footer, 4, (int) localUncompSize);
            fos.write(footer);                      
        }
        System.out.println("Успешно конвертировано в '" + gzipPath + "'.");
    }

    private static void validateCD(byte[] zip, long crc, long comp, long uncomp, byte[] filename) {
        int eocd = -1;
        int maxSearch = Math.max(0, zip.length - 65557);
        for (int i = zip.length - 22; i >= maxSearch; i--) {
            if (read32(zip, i) == 0x06054B50) { eocd = i; break; }
        }
        if (eocd == -1) throw new IllegalArgumentException("Структура EOCD не найдена.");

        if (read16(zip, eocd + 10) != 1 || read16(zip, eocd + 12) != 1) {
            throw new IllegalArgumentException("В архиве должен содержаться ровно 1 файл");
        }

        long cdSize = read32(zip, eocd + 14);
        long cdOffset = read32(zip, eocd + 18);
        int commentLen = read16(zip, eocd + 20);

        if (eocd + 22 + commentLen > zip.length) throw new IllegalArgumentException("EOCD comment выходит за границы");
        if (cdOffset + cdSize > eocd) throw new IllegalArgumentException("Центральный Каталог выходит за рамки структуры");
        
        // Проверка границ Центрального Каталога перед чтением его полей
        if (cdOffset + 46 > zip.length) {
            throw new IllegalArgumentException("Центральный Каталог выходит за границы файла");
        }

        int cdIdx = (int) cdOffset;
        if (read32(zip, cdIdx) != 0x02014B50) throw new IllegalArgumentException("Неверная сигнатура Центрального Каталога.");

        int cdFilenameLen = read16(zip, cdIdx + 28);
        int cdExtraLen = read16(zip, cdIdx + 30);
        int cdCommentLen = read16(zip, cdIdx + 32);

        // Проверка границ переменных полей Центрального Каталога
        if ((long) cdIdx + 46 + cdFilenameLen + cdExtraLen + cdCommentLen > zip.length) {
            throw new IllegalArgumentException("Переменные поля Central Directory выходят за границы файла");
        }

        if (read32(zip, cdIdx + 16) != crc || read32(zip, cdIdx + 20) != comp || read32(zip, cdIdx + 24) != uncomp ||
            cdFilenameLen != filename.length || read32(zip, cdIdx + 42) != 0) {
            throw new IllegalArgumentException("Ошибка рассинхронизации: Метаданные заголовков не совпадают.");
        }

        if (!Arrays.equals(filename, Arrays.copyOfRange(zip, cdIdx + 46, cdIdx + 46 + filename.length))) {
            throw new IllegalArgumentException("Ошибка рассинхронизации: Имена файлов различаются.");
        }
    }

    private static int read16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long read32(byte[] data, int offset) {
        return ((data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8) |
                ((data[offset + 2] & 0xFFL) << 16) | ((data[offset + 3] & 0xFFL) << 24)) & 0xFFFFFFFFL;
    }

    private static void write32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }
}
