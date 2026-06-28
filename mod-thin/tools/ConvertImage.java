import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * JPEG → PNG via javax.imageio del JDK. Minecraft no carga JPEG en
 * resourcepacks (NativeImage solo decodifica PNG); convertimos en build.
 * Idempotente: si el destino existe y es más nuevo que el origen, no
 * recompone.
 */
public final class ConvertImage {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("uso: ConvertImage <in.jpeg> <out.png>");
            System.exit(2);
        }
        File in = new File(args[0]);
        File out = new File(args[1]);
        if (!in.isFile()) {
            System.err.println("falta el archivo de entrada: " + in.getAbsolutePath());
            System.exit(2);
        }
        if (out.isFile() && out.lastModified() >= in.lastModified()) {
            System.out.println("ConvertImage: skip (up-to-date) " + out);
            return;
        }
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        BufferedImage img = ImageIO.read(in);
        if (img == null) {
            System.err.println("no se pudo leer la imagen: " + in.getAbsolutePath());
            System.exit(2);
        }
        if (!ImageIO.write(img, "png", out)) {
            System.err.println("escritor PNG no disponible en el JDK");
            System.exit(2);
        }
        System.out.println("ConvertImage: " + in.getName() + " -> " + out + "  " + img.getWidth() + "x" + img.getHeight());
    }
}
