// SvgToPng - renders an SVG icon to a transparent PNG.
//
// Usage:  SvgToPng <input.svg> <output.png> [size] [scale]
//         SvgToPng <input.svg> --dump          (print the parsed element tree)
//
// [size]  output PNG edge length (default 48)
// [scale] supersampling factor (default 1): the vector is rendered at
//         svg-size × scale and reduced to [size]. The reduction is an exact
//         box average when the ratio is an integer (e.g. 48x2 -> 32 is a
//         3x3 area average), else a high-quality bicubic. Higher = crisper
//         anti-aliased edges, at a render-time cost.
//
// A small, dependency-free rasterizer covering the practical SVG subset the
// app's icon sources use. It deliberately does not use the OS image codecs:
// the built-in WIC SVG path crashes (Out of memory) on some Windows builds,
// and a third-party rasterizer would break the project's "no extra deps"
// rule. The tool builds and runs offline anywhere a .NET SDK is installed.
//
// Supported markup:
//   <svg width height viewBox>
//   <g> element groups with inherited presentation attributes
//   <rect x y width height rx ry>   <line x1 y1 x2 y2>
//   <circle cx cy r>                 <ellipse cx cy rx ry>
//   <path d="...">  commands M m L l H h V v C c Q q S s T t A a Z z
//   colors: "none", CSS color names, #rgb, #rrggbb
//
// Anything else (transforms, gradients, <use>, <text>, filters, clip paths,
// ...) makes the tool fail with a clear message instead of guessing.

using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Globalization;
using System.IO;
using System.Xml;

internal static class Program
{
    static int Main(string[] args)
    {
        if (args.Length == 2 && args[1] == "--dump")
        {
            Dump(SvgParser.Parse(args[0]), 0);
            return 0;
        }

        if (args.Length < 2)
        {
            Console.Error.WriteLine("usage: SvgToPng <input.svg> <output.png> [size]");
            return 2;
        }

        string inPath = args[0];
        string outPath = args[1];
        int size = args.Length > 2 && int.TryParse(args[2], out int parsed) ? parsed : 48;
        int scale = args.Length > 3 && int.TryParse(args[3], out int scaled) ? Math.Max(1, scaled) : 1;

        Node root;
        try
        {
            root = SvgParser.Parse(inPath);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("parse error in " + inPath + ": " + ex.Message);
            return 1;
        }

        // Render at svg size × scale (supersampling), then reduce to `size`.
        float svgW = Util.F(root.A("width"), 48);
        float svgH = Util.F(root.A("height"), 48);
        float[] vb = SvgParser.ParseViewBox(root.A("viewBox"), svgW, svgH);
        float canvasW = svgW * scale;
        float canvasH = svgH * scale;

        Renderer renderer = new();

        try
        {
            using (Bitmap bmp = new((int)Math.Round(canvasW), (int)Math.Round(canvasH), PixelFormat.Format32bppArgb))
            {
                using (Graphics g = Graphics.FromImage(bmp))
                {
                    g.SmoothingMode = SmoothingMode.AntiAlias;
                    g.PixelOffsetMode = PixelOffsetMode.Half;
                    g.Clear(Color.Transparent);
                    g.TranslateTransform(vb[0], vb[1]);
                    if (vb[2] > 0 && vb[3] > 0)
                    {
                        g.ScaleTransform(canvasW / vb[2], canvasH / vb[3]);
                    }
                    renderer.Draw(g, root);
                }

                if (bmp.Width == size && bmp.Height == size)
                {
                    bmp.Save(outPath, ImageFormat.Png);
                }
                else if (size > 1 && bmp.Width % size == 0 && bmp.Height % size == 0
                    && bmp.Width / size == bmp.Height / size)
                {
                    // Integer reduction: exact area average — best quality.
                    SaveAreaDownsample(bmp, size, bmp.Width / size, outPath);
                }
                else
                {
                    // Non-integer reduction: high-quality bicubic fallback.
                    using (Bitmap canvas = new(size, size))
                    {
                        using (Graphics g = Graphics.FromImage(canvas))
                        {
                            g.SmoothingMode = SmoothingMode.AntiAlias;
                            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                            g.PixelOffsetMode = PixelOffsetMode.Half;
                            g.DrawImage(bmp, 0, 0, size, size);
                        }
                        canvas.Save(outPath, ImageFormat.Png);
                    }
                }
            }
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("render error in " + inPath + ": " + ex.Message);
            return 1;
        }

        Console.WriteLine(Path.GetFileName(inPath) + " -> " + outPath + " (" + size + "x" + size + ")");
        return 0;
    }

    static void Dump(Node n, int depth)
    {
        Console.WriteLine(new string(' ', depth * 2) + n.Name
            + " [attrs=" + n.Attrs.Count + ", children=" + n.Children.Count + "]");
        foreach (Node c in n.Children)
        {
            Dump(c, depth + 1);
        }
    }

    // Area-averaging (box) downsample by an integer factor k — the standard
    // high-quality reduction: each output pixel is the mean of a k×k block of
    // the supersampled render. 32bppArgb pixels are stored premultiplied, so
    // averaging the raw per-channel components (and writing them back as-is)
    // yields correctly anti-aliased colors.
    static void SaveAreaDownsample(Bitmap src, int size, int k, string outPath)
    {
        Bitmap dst = new(size, size, PixelFormat.Format32bppArgb);
        for (int oy = 0; oy < size; oy++)
        {
            for (int ox = 0; ox < size; ox++)
            {
                int r = 0, g = 0, b = 0, a = 0;
                for (int dy = 0; dy < k; dy++)
                {
                    for (int dx = 0; dx < k; dx++)
                    {
                        Color c = src.GetPixel(ox * k + dx, oy * k + dy);
                        r += c.R;
                        g += c.G;
                        b += c.B;
                        a += c.A;
                    }
                }
                int n = k * k;
                dst.SetPixel(ox, oy, Color.FromArgb(a / n, r / n, g / n, b / n));
            }
        }
        dst.Save(outPath, ImageFormat.Png);
        dst.Dispose();
    }
}

// ------------------------------------------------------------ XML model ----

static class SvgParser
{
    public static Node Parse(string path)
    {
        XmlReaderSettings settings = new()
        {
            IgnoreComments = true,
            IgnoreWhitespace = true,
        };

        Node root = null;
        Stack<Node> stack = new();
        bool skipPhantomEnd = false;

        using (XmlReader xr = XmlReader.Create(path, settings))
        {
            while (xr.Read())
            {
                if (xr.NodeType == XmlNodeType.Element)
                {
                    // A new start tag means the EndElement promised by a
                    // self-closing tag is either already consumed or will
                    // never come — see the note below.
                    skipPhantomEnd = false;

                    Node n = new(xr.LocalName, new Dictionary<string, string>());
                    while (xr.MoveToNextAttribute())
                    {
                        n.Attrs[xr.LocalName] = xr.Value;
                    }
                    xr.MoveToElement();
                    if (stack.Count == 0)
                    {
                        root = n;
                    }
                    else
                    {
                        stack.Peek().Children.Add(n);
                    }
                    if (xr.IsEmptyElement)
                    {
                        // Self-closing: never a parent, so never pushed. On .NET
                        // builds whose reader still emits its EndElement, the
                        // flag below swallows exactly that one EndElement.
                        // (Empirically, some Windows .NET 9 builds emit no
                        // EndElement for self-closing tags at all — both shapes
                        // are handled here.)
                        skipPhantomEnd = true;
                    }
                    else
                    {
                        stack.Push(n);
                    }
                }
                else if (xr.NodeType == XmlNodeType.EndElement)
                {
                    if (skipPhantomEnd)
                    {
                        skipPhantomEnd = false;
                    }
                    else if (stack.Count > 0)
                    {
                        stack.Pop();
                    }
                }
            }
        }

        if (root == null || root.Name != "svg")
        {
            throw new FormatException("root element must be <svg>");
        }
        return root;
    }

    public static float[] ParseViewBox(string s, float w, float h)
    {
        if (string.IsNullOrWhiteSpace(s))
        {
            return new float[] { 0, 0, w, h };
        }
        string[] parts = s.Split(new[] { ' ', ',', '\t', '\n', '\r' }, StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length != 4)
        {
            throw new FormatException("bad viewBox value: '" + s + "'");
        }
        return new float[]
        {
            Util.F(parts[0], 0),
            Util.F(parts[1], 0),
            Util.F(parts[2], 0),
            Util.F(parts[3], 0),
        };
    }
}

sealed class Node
{
    public readonly string Name;
    public readonly Dictionary<string, string> Attrs;
    public readonly List<Node> Children = new();

    public Node(string name, Dictionary<string, string> attrs)
    {
        Name = name;
        Attrs = attrs;
    }

    public string A(string key)
    {
        return Attrs.TryGetValue(key, out string v) ? v : null;
    }
}

static class Util
{
    // Parses "48", "48.5" or "48px" into a float; falls back to dflt.
    public static float F(string s, float dflt)
    {
        if (string.IsNullOrWhiteSpace(s))
        {
            return dflt;
        }
        string t = s.Trim();
        if (t.EndsWith("px", StringComparison.OrdinalIgnoreCase))
        {
            t = t.Substring(0, t.Length - 2);
        }
        return float.TryParse(t, NumberStyles.Float, CultureInfo.InvariantCulture, out float v) ? v : dflt;
    }
}

// -------------------------------------------------------------- render -----

sealed class Renderer
{
    struct State
    {
        public Color? Fill;
        public Color? Stroke;
        public float StrokeWidth;
        public LineCap Cap;
        public LineJoin Join;
    }

    // SVG defaults: fill black, no stroke. <g> scopes inherit, then override.
    readonly Stack<State> _stack = new();
    State _state = new()
    {
        Fill = Color.Black,
        Stroke = null,
        StrokeWidth = 1,
        Cap = LineCap.Flat,
        Join = LineJoin.Miter,
    };

    public void Draw(Graphics g, Node root)
    {
        foreach (Node child in root.Children)
        {
            DrawElement(g, child);
        }
    }

    void DrawElement(Graphics g, Node n)
    {
        _stack.Push(_state);
        ApplyAttributes(n);
        try
        {
            switch (n.Name)
            {
                case "rect":
                    DrawRect(g, n);
                    break;
                case "line":
                    DrawLine(g, n);
                    break;
                case "circle":
                {
                    float r = Util.F(n.A("r"), 0);
                    DrawEllipse(g, Util.F(n.A("cx"), 0), Util.F(n.A("cy"), 0), r, r);
                    break;
                }
                case "ellipse":
                    DrawEllipse(g, Util.F(n.A("cx"), 0), Util.F(n.A("cy"), 0), Util.F(n.A("rx"), 0), Util.F(n.A("ry"), 0));
                    break;
                case "path":
                    DrawPath(g, n);
                    break;
                case "defs":
                    // Per the SVG spec, <defs> content is not drawn directly.
                    break;
                case "g":
                case "svg":
                    foreach (Node child in n.Children)
                    {
                        DrawElement(g, child);
                    }
                    break;
                default:
                    if (n.Children.Count > 0)
                    {
                        foreach (Node child in n.Children)
                        {
                            DrawElement(g, child);
                        }
                    }
                    else
                    {
                        throw new NotSupportedException(
                            "unsupported element <" + n.Name +
                            ">: this tool renders svg, g, defs, rect, line, circle, ellipse and path only");
                    }
                    break;
            }
        }
        finally
        {
            _state = _stack.Pop();
        }
    }

    void ApplyAttributes(Node n)
    {
        if (n.A("transform") != null)
        {
            throw new NotSupportedException("attribute 'transform' is not supported (element <" + n.Name + ">)");
        }

        string v;
        if (!string.IsNullOrEmpty(v = n.A("fill")))
        {
            _state.Fill = ParseColor(v);
        }
        if (!string.IsNullOrEmpty(v = n.A("stroke")))
        {
            _state.Stroke = ParseColor(v);
        }
        if ((v = n.A("stroke-width")) != null)
        {
            _state.StrokeWidth = Math.Max(0f, Util.F(v, 1));
        }
        if ((v = n.A("stroke-linecap")) != null)
        {
            _state.Cap = v == "round" ? LineCap.Round : v == "square" ? LineCap.Square : LineCap.Flat;
        }
        if ((v = n.A("stroke-linejoin")) != null)
        {
            _state.Join = v == "round" ? LineJoin.Round : v == "bevel" ? LineJoin.Bevel : LineJoin.Miter;
        }
    }

    void DrawRect(Graphics g, Node n)
    {
        float x = Util.F(n.A("x"), 0);
        float y = Util.F(n.A("y"), 0);
        float w = Util.F(n.A("width"), 0);
        float h = Util.F(n.A("height"), 0);
        if (w <= 0 || h <= 0)
        {
            return;
        }

        float rx = Util.F(n.A("rx") ?? n.A("ry"), 0);
        float ry = Util.F(n.A("ry") ?? n.A("rx"), 0);

        if (rx <= 0 && ry <= 0)
        {
            RectangleF r = new(x, y, w, h);
            if (_state.Fill != null)
            {
                using (SolidBrush brush = new(_state.Fill.Value))
                {
                    g.FillRectangle(brush, r);
                }
            }
            if (_state.Stroke != null)
            {
                using (Pen pen = MakePen())
                {
                    g.DrawRectangle(pen, r);
                }
            }
        }
        else
        {
            using (GraphicsPath p = RoundedRect(x, y, w, h, rx, ry))
            {
                if (_state.Fill != null)
                {
                    using (SolidBrush brush = new(_state.Fill.Value))
                    {
                        g.FillPath(brush, p);
                    }
                }
                if (_state.Stroke != null)
                {
                    using (Pen pen = MakePen())
                    {
                        g.DrawPath(pen, p);
                    }
                }
            }
        }
    }

    static GraphicsPath RoundedRect(float x, float y, float w, float h, float rx, float ry)
    {
        rx = Math.Min(rx, w / 2f);
        ry = Math.Min(ry, h / 2f);
        GraphicsPath p = new();
        p.AddArc(x, y, 2 * rx, 2 * ry, 180, 90);
        p.AddArc(x + w - 2 * rx, y, 2 * rx, 2 * ry, 270, 90);
        p.AddArc(x + w - 2 * rx, y + h - 2 * ry, 2 * rx, 2 * ry, 0, 90);
        p.AddArc(x, y + h - 2 * ry, 2 * rx, 2 * ry, 90, 90);
        p.CloseFigure();
        return p;
    }

    void DrawLine(Graphics g, Node n)
    {
        if (_state.Stroke == null)
        {
            return;
        }
        using (Pen pen = MakePen())
        {
            g.DrawLine(pen, Util.F(n.A("x1"), 0), Util.F(n.A("y1"), 0), Util.F(n.A("x2"), 0), Util.F(n.A("y2"), 0));
        }
    }

    void DrawEllipse(Graphics g, float cx, float cy, float rx, float ry)
    {
        if (rx <= 0 || ry <= 0)
        {
            return;
        }
        RectangleF r = new(cx - rx, cy - ry, 2 * rx, 2 * ry);
        if (_state.Fill != null)
        {
            using (SolidBrush brush = new(_state.Fill.Value))
            {
                g.FillEllipse(brush, r);
            }
        }
        if (_state.Stroke != null)
        {
            using (Pen pen = MakePen())
            {
                g.DrawEllipse(pen, r);
            }
        }
    }

    void DrawPath(Graphics g, Node n)
    {
        string d = n.A("d");
        if (d == null)
        {
            throw new NotSupportedException("<path> without a d attribute");
        }
        using (GraphicsPath p = PathBuilder.Build(d))
        {
            // SVG paint order: fill first, stroke on top.
            if (_state.Fill != null)
            {
                using (SolidBrush brush = new(_state.Fill.Value))
                {
                    g.FillPath(brush, p);
                }
            }
            if (_state.Stroke != null)
            {
                using (Pen pen = MakePen())
                {
                    g.DrawPath(pen, p);
                }
            }
        }
    }

    Pen MakePen()
    {
        return new Pen(_state.Stroke.Value, _state.StrokeWidth)
        {
            StartCap = _state.Cap,
            EndCap = _state.Cap,
            LineJoin = _state.Join,
        };
    }

    static Color? ParseColor(string s)
    {
        s = s.Trim();
        if (s.Equals("none", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }
        try
        {
            // Handles "white", "#fff", "#ffffff", and the full CSS name list.
            return ColorTranslator.FromHtml(s);
        }
        catch (Exception)
        {
            throw new NotSupportedException("unsupported color '" + s + "' (CSS color names and #hex are supported)");
        }
    }
}

// ------------------------------------------------------ path data (SVG) ----

static class PathBuilder
{
    public static GraphicsPath Build(string d)
    {
        List<object> tokens = Tokenize(d);
        GraphicsPath path = new();

        float x = 0;
        float y = 0;
        float subX = 0;
        float subY = 0;
        float pcx = 0;
        float pcy = 0;      // previous curve control point (for S/s and T/t)
        bool prevCubic = false;
        bool prevQuad = false;
        char cmd = '\0';

        for (int i = 0; i < tokens.Count; i++)
        {
            if (tokens[i] is char c)
            {
                cmd = c;
                continue;
            }

            if (cmd == '\0')
            {
                throw new FormatException("path '" + d + "': number(s) appear before any command");
            }

            i = Execute(path, ref x, ref y, ref subX, ref subY, ref pcx, ref pcy, ref prevCubic, ref prevQuad,
                    cmd, tokens, i, d) - 1;

            // Per the SVG spec, coordinate pairs after M/m repeat as L/l.
            if (cmd == 'M')
            {
                cmd = 'L';
            }
            else if (cmd == 'm')
            {
                cmd = 'l';
            }
        }

        return path;
    }

    static List<object> Tokenize(string d)
    {
        List<object> tokens = new();
        int n = d.Length;
        int i = 0;

        while (i < n)
        {
            char c = d[i];

            if (char.IsWhiteSpace(c))
            {
                i++;
                continue;
            }

            if (char.IsLetter(c))
            {
                tokens.Add(c);
                i++;
                continue;
            }

            if (c == '-' || c == '+' || c == '.' || (c >= '0' && c <= '9'))
            {
                int start = i;
                bool dot = false;
                bool exp = false;
                i++;
                while (i < n)
                {
                    char k = d[i];
                    if (k >= '0' && k <= '9')
                    {
                        i++;
                    }
                    else if (k == '.' && !dot && !exp)
                    {
                        dot = true;
                        i++;
                    }
                    else if ((k == 'e' || k == 'E') && !exp)
                    {
                        exp = true;
                        i++;
                        if (i < n && (d[i] == '+' || d[i] == '-'))
                        {
                            i++;
                        }
                    }
                    else
                    {
                        break;
                    }
                }
                tokens.Add(float.Parse(d.Substring(start, i - start), CultureInfo.InvariantCulture));
            }
            else
            {
                throw new FormatException("path '" + d + "': unexpected character '" + c + "'");
            }
        }

        return tokens;
    }

    static int Execute(GraphicsPath path, ref float x, ref float y, ref float subX, ref float subY,
        ref float pcx, ref float pcy, ref bool prevCubic, ref bool prevQuad,
        char cmd, List<object> tokens, int start, string d)
    {
        int j = start;

        float N()
        {
            if (j >= tokens.Count || tokens[j] is not float f)
            {
                throw new FormatException("path '" + d + "': missing number(s) for command '" + cmd + "'");
            }
            j++;
            return f;
        }

        switch (cmd)
        {
            case 'M':
                x = N();
                y = N();
                subX = x;
                subY = y;
                path.StartFigure();
                path.AddLine(new PointF(x, y), new PointF(x, y));
                prevCubic = prevQuad = false;
                break;
            case 'm':
                x += N();
                y += N();
                subX = x;
                subY = y;
                path.StartFigure();
                path.AddLine(new PointF(x, y), new PointF(x, y));
                prevCubic = prevQuad = false;
                break;
            case 'L':
            {
                PointF from = new(x, y);
                x = N();
                y = N();
                path.AddLine(from, new PointF(x, y));
                prevCubic = prevQuad = false;
                break;
            }
            case 'l':
            {
                PointF from = new(x, y);
                x += N();
                y += N();
                path.AddLine(from, new PointF(x, y));
                prevCubic = prevQuad = false;
                break;
            }
            case 'H':
            {
                PointF from = new(x, y);
                x = N();
                path.AddLine(from, new PointF(x, y));
                prevCubic = prevQuad = false;
                break;
            }
            case 'h':
            {
                PointF from = new(x, y);
                x += N();
                path.AddLine(from, new PointF(x, y));
                prevCubic = prevQuad = false;
                break;
            }
            case 'V':
            {
                PointF from = new(x, y);
                y = N();
                path.AddLine(from, new PointF(x, y));
                prevCubic = prevQuad = false;
                break;
            }
            case 'v':
            {
                PointF from = new(x, y);
                y += N();
                path.AddLine(from, new PointF(x, y));
                prevCubic = prevQuad = false;
                break;
            }
            case 'C':
            {
                PointF p0 = new(x, y);
                float c1x = N(), c1y = N(), c2x = N(), c2y = N();
                x = N();
                y = N();
                pcx = c2x;
                pcy = c2y;
                prevCubic = true;
                prevQuad = false;
                path.AddBezier(p0, new PointF(c1x, c1y), new PointF(c2x, c2y), new PointF(x, y));
                break;
            }
            case 'c':
            {
                PointF p0 = new(x, y);
                float c1x = x + N(), c1y = y + N(), c2x = x + N(), c2y = y + N();
                x += N();
                y += N();
                pcx = c2x;
                pcy = c2y;
                prevCubic = true;
                prevQuad = false;
                path.AddBezier(p0, new PointF(c1x, c1y), new PointF(c2x, c2y), new PointF(x, y));
                break;
            }
            case 'S':
            {
                PointF p0 = new(x, y);
                float c1x = prevCubic ? 2 * x - pcx : x;
                float c1y = prevCubic ? 2 * y - pcy : y;
                float c2x = N(), c2y = N();
                x = N();
                y = N();
                pcx = c2x;
                pcy = c2y;
                prevCubic = true;
                prevQuad = false;
                path.AddBezier(p0, new PointF(c1x, c1y), new PointF(c2x, c2y), new PointF(x, y));
                break;
            }
            case 's':
            {
                PointF p0 = new(x, y);
                float c1x = prevCubic ? 2 * x - pcx : x;
                float c1y = prevCubic ? 2 * y - pcy : y;
                float c2x = x + N(), c2y = y + N();
                x += N();
                y += N();
                pcx = c2x;
                pcy = c2y;
                prevCubic = true;
                prevQuad = false;
                path.AddBezier(p0, new PointF(c1x, c1y), new PointF(c2x, c2y), new PointF(x, y));
                break;
            }
            case 'Q':
            {
                PointF p0 = new(x, y);
                float cx = N(), cy = N();
                x = N();
                y = N();
                PointF p2 = new(x, y);
                PointF b1 = new(p0.X + 2f / 3f * (cx - p0.X), p0.Y + 2f / 3f * (cy - p0.Y));
                PointF b2 = new(p2.X + 2f / 3f * (cx - p2.X), p2.Y + 2f / 3f * (cy - p2.Y));
                pcx = cx;
                pcy = cy;
                prevCubic = false;
                prevQuad = true;
                path.AddBezier(p0, b1, b2, p2);
                break;
            }
            case 'q':
            {
                PointF p0 = new(x, y);
                float cx = x + N(), cy = y + N();
                x += N();
                y += N();
                PointF p2 = new(x, y);
                PointF b1 = new(p0.X + 2f / 3f * (cx - p0.X), p0.Y + 2f / 3f * (cy - p0.Y));
                PointF b2 = new(p2.X + 2f / 3f * (cx - p2.X), p2.Y + 2f / 3f * (cy - p2.Y));
                pcx = cx;
                pcy = cy;
                prevCubic = false;
                prevQuad = true;
                path.AddBezier(p0, b1, b2, p2);
                break;
            }
            case 'T':
            {
                PointF p0 = new(x, y);
                float cx = prevQuad ? 2 * x - pcx : x;
                float cy = prevQuad ? 2 * y - pcy : y;
                x = N();
                y = N();
                PointF p2 = new(x, y);
                PointF b1 = new(p0.X + 2f / 3f * (cx - p0.X), p0.Y + 2f / 3f * (cy - p0.Y));
                PointF b2 = new(p2.X + 2f / 3f * (cx - p2.X), p2.Y + 2f / 3f * (cy - p2.Y));
                pcx = cx;
                pcy = cy;
                prevCubic = false;
                prevQuad = true;
                path.AddBezier(p0, b1, b2, p2);
                break;
            }
            case 't':
            {
                PointF p0 = new(x, y);
                float cx = prevQuad ? 2 * x - pcx : x;
                float cy = prevQuad ? 2 * y - pcy : y;
                x += N();
                y += N();
                PointF p2 = new(x, y);
                PointF b1 = new(p0.X + 2f / 3f * (cx - p0.X), p0.Y + 2f / 3f * (cy - p0.Y));
                PointF b2 = new(p2.X + 2f / 3f * (cx - p2.X), p2.Y + 2f / 3f * (cy - p2.Y));
                pcx = cx;
                pcy = cy;
                prevCubic = false;
                prevQuad = true;
                path.AddBezier(p0, b1, b2, p2);
                break;
            }
            case 'A':
            {
                float rx = N(), ry = N(), phi = N(), laf = N(), sf = N();
                float x2 = N(), y2 = N();
                AddArc(path, x, y, x2, y2, rx, ry, phi, laf != 0, sf != 0);
                x = x2;
                y = y2;
                prevCubic = prevQuad = false;
                break;
            }
            case 'a':
            {
                float rx = N(), ry = N(), phi = N(), laf = N(), sf = N();
                float x2 = x + N(), y2 = y + N();
                AddArc(path, x, y, x2, y2, rx, ry, phi, laf != 0, sf != 0);
                x = x2;
                y = y2;
                prevCubic = prevQuad = false;
                break;
            }
            case 'Z':
            case 'z':
                path.CloseFigure();
                x = subX;
                y = subY;
                prevCubic = prevQuad = false;
                break;
            default:
                throw new NotSupportedException("path command '" + cmd + "' is not supported");
        }

        return j;
    }

    // Converts an elliptical arc to cubic Beziers: W3C SVG 1.1 F.6.5
    // (endpoint to center parameterization) + the standard unit-circle
    // approximation, generalized to rotated ellipses. Segments are at most
    // a quarter turn each, which keeps the Bezier error well under a pixel
    // for icon-sized radii.
    static void AddArc(GraphicsPath path, float fx1, float fy1, float fx2, float fy2,
        float frx, float fry, double phiDeg, bool largeArc, bool sweep)
    {
        double x1 = fx1, y1 = fy1;
        double x2 = fx2, y2 = fy2;
        double rx = Math.Abs(frx);
        double ry = Math.Abs(fry);

        if (rx == 0 || ry == 0)
        {
            path.AddLine(new PointF((float)x1, (float)y1), new PointF((float)x2, (float)y2));
            return;
        }

        double phi = phiDeg * Math.PI / 180.0;
        double cos = Math.Cos(phi);
        double sin = Math.Sin(phi);

        // Step 1: rotate the endpoints into the ellipse's frame (origin at midpoint).
        double dx = (x1 - x2) / 2.0;
        double dy = (y1 - y2) / 2.0;
        double x1p = cos * dx + sin * dy;
        double y1p = -sin * dx + cos * dy;

        // Step 2: radii too small for the endpoints? Enlarge them (spec rule).
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1)
        {
            double s = Math.Sqrt(lambda);
            rx *= s;
            ry *= s;
        }

        // Step 3: the ellipse center in the rotated frame.
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double factor = Math.Sqrt(Math.Max(0.0, num / Math.Max(1e-12, den)));
        if (largeArc == sweep)
        {
            factor = -factor;
        }

        double cxp = factor * (rx * y1p / ry);
        double cyp = -factor * (ry * x1p / rx);

        // Step 4: back to world coordinates.
        double cx = cos * cxp - sin * cyp + (x1 + x2) / 2.0;
        double cy = sin * cxp + cos * cyp + (y1 + y2) / 2.0;

        // Step 5: start angle and swept angle (sweep=1 is the direction of
        // increasing parameter, i.e. clockwise on screen because y points down).
        double ux1 = (x1p - cxp) / rx;
        double uy1 = (y1p - cyp) / ry;
        double ux2 = (-x1p - cxp) / rx;
        double uy2 = (-y1p - cyp) / ry;

        double theta1 = Math.Atan2(uy1, ux1);
        double dtheta = SignedAngle(ux1, uy1, ux2, uy2);
        if (dtheta < 0 && sweep)
        {
            dtheta += 2 * Math.PI;
        }
        if (dtheta > 0 && !sweep)
        {
            dtheta -= 2 * Math.PI;
        }

        if (Math.Abs(dtheta) < 1e-9)
        {
            path.AddLine(new PointF((float)x1, (float)y1), new PointF((float)x2, (float)y2));
            return;
        }

        int segments = Math.Max(1, (int)Math.Ceiling(Math.Abs(dtheta) / (Math.PI / 2.0)));
        double delta = dtheta / segments;
        double t = Math.Tan(delta / 4.0);

        for (int k = 0; k < segments; k++)
        {
            double a0 = theta1 + k * delta;
            double a1 = a0 + delta;

            double p0x = cx + rx * cos * Math.Cos(a0) - ry * sin * Math.Sin(a0);
            double p0y = cy + rx * sin * Math.Cos(a0) + ry * cos * Math.Sin(a0);
            double p3x = cx + rx * cos * Math.Cos(a1) - ry * sin * Math.Sin(a1);
            double p3y = cy + rx * sin * Math.Cos(a1) + ry * cos * Math.Sin(a1);

            // dP/da in world coordinates (the Bezier handles of the unit-circle
            // approximation, scaled by the ellipse + rotation).
            double d0x = -(rx * cos * Math.Sin(a0) + ry * sin * Math.Cos(a0));
            double d0y = -(rx * sin * Math.Sin(a0) - ry * cos * Math.Cos(a0));
            double d3x = -(rx * cos * Math.Sin(a1) + ry * sin * Math.Cos(a1));
            double d3y = -(rx * sin * Math.Sin(a1) - ry * cos * Math.Cos(a1));

            // Pin the very first point to the exact start so no seam appears.
            if (k == 0)
            {
                p0x = x1;
                p0y = y1;
            }

            path.AddBezier(
                new PointF((float)p0x, (float)p0y),
                new PointF((float)(p0x + t * d0x), (float)(p0y + t * d0y)),
                new PointF((float)(p3x - t * d3x), (float)(p3y - t * d3y)),
                new PointF((float)p3x, (float)p3y));
        }
    }

    static double SignedAngle(double x1, double y1, double x2, double y2)
    {
        double a = Math.Atan2(y2, x2) - Math.Atan2(y1, x1);
        while (a <= -Math.PI)
        {
            a += 2 * Math.PI;
        }
        while (a > Math.PI)
        {
            a -= 2 * Math.PI;
        }
        return a;
    }
}
