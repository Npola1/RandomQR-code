import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.imageio.ImageIO;

/** Self-contained Version 3-L QR code generator. Requires only the Java JDK. */
public class RandomQRCode {
    private static final int SIZE = 29; // QR version 3
    private static final int DATA_CODEWORDS = 55;
    private static final int ECC_CODEWORDS = 15;
    private static final int BORDER = 4;
    private static final int SCALE = 10;

    private final boolean[][] modules = new boolean[SIZE][SIZE];
    private final boolean[][] reserved = new boolean[SIZE][SIZE];

    public static void main(String[] args) {
        String randomData = UUID.randomUUID().toString();
        try {
            RandomQRCode qr = new RandomQRCode();
            qr.encode(randomData);
            qr.save("random_qr.png");
            System.out.println("QR Code Content: " + randomData);
            System.out.println("Saved as random_qr.png");
        } catch (IOException e) {
            System.err.println("Unable to save QR code: " + e.getMessage());
        }
    }

    private void encode(String text) {
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        if (input.length > 53) {
            throw new IllegalArgumentException("This QR code version supports at most 53 UTF-8 bytes.");
        }

        // Byte-mode header (4 bits), byte length (8 bits), then UTF-8 data.
        int[] data = new int[DATA_CODEWORDS];
        int bitLength = 0;
        bitLength = appendBits(data, bitLength, 0b0100, 4);
        bitLength = appendBits(data, bitLength, input.length, 8);
        for (int i = 0; i < input.length; i++) {
            bitLength = appendBits(data, bitLength, input[i] & 0xFF, 8);
        }
        bitLength = appendBits(data, bitLength, 0,
                Math.min(4, DATA_CODEWORDS * 8 - bitLength));
        bitLength = (bitLength + 7) / 8 * 8;
        int usedBytes = bitLength / 8;
        for (int i = usedBytes; i < DATA_CODEWORDS; i++) {
            data[i] = (i - usedBytes) % 2 == 0 ? 0xEC : 0x11;
        }

        int[] ecc = reedSolomonRemainder(data, ECC_CODEWORDS);
        int[] codewords = new int[DATA_CODEWORDS + ECC_CODEWORDS];
        System.arraycopy(data, 0, codewords, 0, DATA_CODEWORDS);
        System.arraycopy(ecc, 0, codewords, DATA_CODEWORDS, ECC_CODEWORDS);

        drawFunctionPatterns();
        drawCodewords(codewords);
        applyMaskZero();
    }

    private static int appendBits(int[] buffer, int length, int value, int count) {
        for (int i = count - 1; i >= 0; i--) {
            buffer[length >>> 3] |= ((value >>> i) & 1) << (7 - (length & 7));
            length++;
        }
        return length;
    }

    private void setFunction(int x, int y, boolean dark) {
        modules[y][x] = dark;
        reserved[y][x] = true;
    }

    private void drawFunctionPatterns() {
        // Timing patterns, then finder patterns and their separators.
        for (int i = 0; i < SIZE; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(SIZE - 4, 3);
        drawFinder(3, SIZE - 4);

        // The only alignment pattern in a version-3 QR code.
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int distance = Math.max(Math.abs(dx), Math.abs(dy));
                setFunction(22 + dx, 22 + dy, distance != 1);
            }
        }
        drawFormatBits();
    }

    private void drawFinder(int centerX, int centerY) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = centerX + dx;
                int y = centerY + dy;
                if (x >= 0 && x < SIZE && y >= 0 && y < SIZE) {
                    int distance = Math.max(Math.abs(dx), Math.abs(dy));
                    setFunction(x, y, distance != 2 && distance != 4);
                }
            }
        }
    }

    private void drawFormatBits() {
        // Error correction level L has format value 1; use QR mask 0.
        int value = 1 << 3;
        int remainder = value;
        for (int i = 0; i < 10; i++) {
            remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537);
        }
        int bits = ((value << 10) | remainder) ^ 0x5412;

        for (int i = 0; i <= 5; i++) setFunction(8, i, getBit(bits, i));
        setFunction(8, 7, getBit(bits, 6));
        setFunction(8, 8, getBit(bits, 7));
        setFunction(7, 8, getBit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, getBit(bits, i));

        for (int i = 0; i < 8; i++) setFunction(SIZE - 1 - i, 8, getBit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(8, SIZE - 15 + i, getBit(bits, i));
        setFunction(8, SIZE - 8, true); // Always-dark module.
    }

    private static boolean getBit(int value, int index) {
        return ((value >>> index) & 1) != 0;
    }

    private void drawCodewords(int[] codewords) {
        int bitIndex = 0;
        for (int right = SIZE - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5; // Skip timing column.
            for (int vertical = 0; vertical < SIZE; vertical++) {
                int y = ((right + 1) & 2) == 0 ? SIZE - 1 - vertical : vertical;
                for (int offset = 0; offset < 2; offset++) {
                    int x = right - offset;
                    if (!reserved[y][x] && bitIndex < codewords.length * 8) {
                        modules[y][x] = getBit(codewords[bitIndex >>> 3],
                                7 - (bitIndex & 7));
                        bitIndex++;
                    }
                }
            }
        }
        if (bitIndex != codewords.length * 8) {
            throw new IllegalStateException("Unexpected QR code capacity");
        }
    }

    private void applyMaskZero() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (!reserved[y][x] && (x + y) % 2 == 0) {
                    modules[y][x] = !modules[y][x];
                }
            }
        }
    }

    private static int[] reedSolomonRemainder(int[] data, int degree) {
        int[] divisor = new int[degree];
        divisor[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                divisor[j] = gfMultiply(divisor[j], root);
                if (j + 1 < degree) divisor[j] ^= divisor[j + 1];
            }
            root = gfMultiply(root, 2);
        }

        int[] remainder = new int[degree];
        for (int i = 0; i < data.length; i++) {
            int factor = data[i] ^ remainder[0];
            System.arraycopy(remainder, 1, remainder, 0, degree - 1);
            remainder[degree - 1] = 0;
            for (int j = 0; j < degree; j++) {
                remainder[j] ^= gfMultiply(divisor[j], factor);
            }
        }
        return remainder;
    }

    private static int gfMultiply(int x, int y) {
        int product = 0;
        for (int i = 7; i >= 0; i--) {
            product = (product << 1) ^ ((product >>> 7) * 0x11D);
            product ^= ((y >>> i) & 1) * x;
        }
        return product;
    }

    private void save(String filename) throws IOException {
        int imageSize = (SIZE + BORDER * 2) * SCALE;
        BufferedImage image = new BufferedImage(
                imageSize, imageSize, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < imageSize; y++) {
            for (int x = 0; x < imageSize; x++) {
                int moduleX = x / SCALE - BORDER;
                int moduleY = y / SCALE - BORDER;
                boolean dark = moduleX >= 0 && moduleX < SIZE
                        && moduleY >= 0 && moduleY < SIZE
                        && modules[moduleY][moduleX];
                image.setRGB(x, y, dark ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
            }
        }
        ImageIO.write(image, "png", new File(filename));
    }
}