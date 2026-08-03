import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class CropShots {
    public static void main(String[] args) throws Exception {
        String[] files = {"home_amber.png", "home_check.png", "leave_dialog.png", "leave_dialog2.png"};
        for (String f : files) {
            File in = new File("../" + f);
            BufferedImage img = ImageIO.read(in);
            int w = img.getWidth();
            int targetH = w * 2; // enforce max 2:1 ratio
            int h = img.getHeight();
            if (h > targetH) {
                int crop = h - targetH;
                int top = crop / 2; // center-crop
                BufferedImage cropped = img.getSubimage(0, top, w, targetH);
                File out = new File(f.replace(".png", "-shot.png"));
                ImageIO.write(cropped, "png", out);
                System.out.println("Wrote " + out.getAbsolutePath() + " " + w + "x" + targetH);
            }
        }
    }
}
