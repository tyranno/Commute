import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class IconGen {
    static final Color PURPLE = new Color(0x66, 0x50, 0xA4);
    static final Color WHITE = Color.WHITE;

    public static void main(String[] args) throws Exception {
        writeIcon(512, new File("icon-512.png"), true);
        writeFeatureGraphic(new File("feature-graphic-1024x500.png"));
    }

    static void drawGlyph(Graphics2D g, double k, double ox, double oy) {
        // pin tail
        Path2D tail = new Path2D.Double();
        tail.moveTo(ox + 46 * k, oy + 56 * k);
        tail.lineTo(ox + 62 * k, oy + 56 * k);
        tail.lineTo(ox + 54 * k, oy + 84 * k);
        tail.closePath();
        g.setColor(WHITE);
        g.fill(tail);

        // pin head (circle r=18 centered 54,44)
        Ellipse2D head = new Ellipse2D.Double(ox + (54 - 18) * k, oy + (44 - 18) * k, 36 * k, 36 * k);
        g.fill(head);

        // clock hands
        g.setColor(PURPLE);
        g.setStroke(new BasicStroke((float) (3 * k), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(ox + 54 * k, oy + 44 * k, ox + 54 * k, oy + 33 * k));
        g.draw(new Line2D.Double(ox + 54 * k, oy + 44 * k, ox + 63 * k, oy + 49 * k));

        // pivot
        Ellipse2D pivot = new Ellipse2D.Double(ox + (54 - 2.2) * k, oy + (44 - 2.2) * k, 4.4 * k, 4.4 * k);
        g.fill(pivot);
    }

    static void writeIcon(int size, File out, boolean square) throws Exception {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(PURPLE);
        g.fillRect(0, 0, size, size);
        double k = size / 108.0;
        drawGlyph(g, k, 0, 0);
        g.dispose();
        ImageIO.write(img, "png", out);
        System.out.println("Wrote " + out.getAbsolutePath());
    }

    static void writeFeatureGraphic(File out) throws Exception {
        int w = 1024, h = 500;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(PURPLE);
        g.fillRect(0, 0, w, h);

        // glyph on a white rounded square, left side
        double glyphSize = 260;
        double gx = 70, gy = (h - glyphSize) / 2.0;
        g.setColor(WHITE);
        RoundRectangle2D badge = new RoundRectangle2D.Double(gx, gy, glyphSize, glyphSize, 64, 64);
        g.fill(badge);
        double k = (glyphSize * 0.8) / 108.0;
        double ox = gx + glyphSize * 0.1, oy = gy + glyphSize * 0.1;
        // draw glyph but with purple pin instead of white (since background is white here)
        Graphics2D g2 = (Graphics2D) g.create();
        drawGlyphOnWhite(g2, k, ox, oy);
        g2.dispose();

        // text
        g.setColor(WHITE);
        g.setFont(new Font("Malgun Gothic", Font.BOLD, 68));
        String title = "Commute";
        double textX = gx + glyphSize + 55;
        double textY = h / 2.0 - 15;
        g.drawString(title, (float) textX, (float) textY);

        g.setFont(new Font("Malgun Gothic", Font.PLAIN, 34));
        String subtitle = "Wi-Fi·BLE 자동 출퇴근 기록";
        g.drawString(subtitle, (float) textX, (float) (textY + 55));

        g.dispose();
        ImageIO.write(img, "png", out);
        System.out.println("Wrote " + out.getAbsolutePath());
    }

    static void drawGlyphOnWhite(Graphics2D g, double k, double ox, double oy) {
        Path2D tail = new Path2D.Double();
        tail.moveTo(ox + 46 * k, oy + 56 * k);
        tail.lineTo(ox + 62 * k, oy + 56 * k);
        tail.lineTo(ox + 54 * k, oy + 84 * k);
        tail.closePath();
        g.setColor(PURPLE);
        g.fill(tail);
        Ellipse2D head = new Ellipse2D.Double(ox + (54 - 18) * k, oy + (44 - 18) * k, 36 * k, 36 * k);
        g.fill(head);
        g.setColor(WHITE);
        g.setStroke(new BasicStroke((float) (3 * k), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(ox + 54 * k, oy + 44 * k, ox + 54 * k, oy + 33 * k));
        g.draw(new Line2D.Double(ox + 54 * k, oy + 44 * k, ox + 63 * k, oy + 49 * k));
        Ellipse2D pivot = new Ellipse2D.Double(ox + (54 - 2.2) * k, oy + (44 - 2.2) * k, 4.4 * k, 4.4 * k);
        g.fill(pivot);
    }
}
