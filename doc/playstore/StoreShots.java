import java.awt.RenderingHints;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * Turns raw `adb screencap` captures into Play Store phone screenshots, in place.
 *
 * Play rejects a screenshot whose long side is more than twice its short side. A raw capture from
 * this phone is 1440x3120 — ratio 2.167 — so every screenshot taken straight off the device fails
 * that check, which is easy to miss until the upload is refused.
 *
 * Cropping the system bars is what fixes it, and it is worth doing on its own: the status bar
 * carries the USB-debugging icon, notification badges and a battery percentage that have nothing
 * to do with the app, and the gesture bar is just chrome. Removing both brings 3120 down to 2840
 * (ratio 1.972) and leaves a cleaner image. If a future device still exceeds 2:1 after that, the
 * remainder is center-cropped rather than letterboxed, so the app never sits in bars.
 *
 * Output is 1080 wide — the size Play lists for phone screenshots — written as RGB with no alpha
 * channel, since a PNG carrying transparency can be refused.
 *
 * Run from doc/playstore:  java StoreShots.java [directory]     (default: screenshots)
 *
 * Overwrites its inputs. The raw captures are in git history if one is ever needed again.
 */
public class StoreShots {

    /** Status bar and gesture bar on the capture device (1440x3120, ~505dpi). Both are measured in
     * source pixels, so re-measure if captures start coming from a different phone. */
    private static final int CROP_TOP = 120;
    private static final int CROP_BOTTOM = 160;

    /** Play's rule: the long side may be at most twice the short side. */
    private static final double MAX_RATIO = 2.0;

    /** Play's listed width for phone screenshots. Height follows from the cropped aspect. */
    private static final int TARGET_WIDTH = 1080;

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "screenshots");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
        if (files == null || files.length == 0) {
            System.out.println("No PNGs in " + dir.getAbsolutePath());
            return;
        }
        Arrays.sort(files);

        for (File file : files) {
            BufferedImage src = ImageIO.read(file);
            int w = src.getWidth();
            int h = src.getHeight();

            // Trim the system bars, guarding against an image too short to take the full crop.
            int top = Math.min(CROP_TOP, Math.max(0, h / 4));
            int bottom = Math.min(CROP_BOTTOM, Math.max(0, h / 4));
            int croppedH = h - top - bottom;

            // Still too tall for Play? Take the rest off both ends so the frame stays centred.
            int maxH = (int) (w * MAX_RATIO);
            if (croppedH > maxH) {
                int extra = croppedH - maxH;
                top += extra / 2;
                croppedH = maxH;
            }
            BufferedImage cropped = src.getSubimage(0, top, w, croppedH);

            int outW = TARGET_WIDTH;
            int outH = Math.round(croppedH * (outW / (float) w));

            // TYPE_INT_RGB, not ARGB: an alpha channel can get a store screenshot refused.
            BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(cropped, 0, 0, outW, outH, null);
            g.dispose();

            ImageIO.write(out, "png", file);

            double ratio = Math.max(outW, outH) / (double) Math.min(outW, outH);
            System.out.printf("%-24s %dx%d -> %dx%d  ratio %.3f  %s%n",
                file.getName(), w, h, outW, outH, ratio, ratio <= MAX_RATIO ? "ok" : "STILL OVER 2:1");
        }
    }
}
