import javax.imageio.ImageIO;
import java.io.File;
public class Dim {
    public static void main(String[] a) throws Exception {
        for (String f : new String[]{"home_amber-shot.png","home_check-shot.png","leave_dialog-shot.png","leave_dialog2-shot.png","icon-512.png"}) {
            var img = ImageIO.read(new File(f));
            System.out.println(f + " " + img.getWidth() + "x" + img.getHeight());
        }
    }
}
