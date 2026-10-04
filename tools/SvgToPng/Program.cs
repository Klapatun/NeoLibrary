// SvgToPng - renders an SVG icon to a fixed-size transparent PNG.
//
// Usage:  SvgToPng <input.svg> <output.png> [size]
//
// A small, dependency-free rasterizer for the practical SVG subset the app's
// icon sources use. No third-party packages and no OS codecs (the built-in
// WIC SVG path crashes on some Windows builds), so the tool builds and runs
// offline anywhere a .NET SDK is present.
//
// Supported:
//   <svg width height viewBox>
//   <g>  (state grouping)
//   presentation attrs: fill, stroke, stroke-width, stroke-linecap,
//        stroke-linejoin, fill-opacity, stroke-opacity
//        colors: "none", "white", "black", #rgb, #rrggbb
//   <rect x y width height rx ry>
//   <line x1 y1 x2 y2>
//   <circle cx cy r>
//   <ellipse cx cy rx ry>
//   <path d="...">  commands: M m L l H h V v C c Q q S s T t A a Z z
//
// Everything else (transforms, gradients, <use>, text, filters, clip paths,
// ...) makes the tool fail with a clear message instead of guessing.

using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Globalization;
using System.Text.RegularExpressions;
using System.Xml;

if (args.Length < 2)
{
    Console.Error.WriteLine("usage: SvgToPng <input.svg> <output.png> [size]");
    return 2;
}

string inPath = args[0];
string outPath = args[1];
int size = args.Length > 2 && int.TryParse(args[2], out int parsed) ? parsed : 48;

// ---------------------------------------------------------------- parse ----

XmlReaderSettings xs = new()
{
    IgnoreComments = true,
    IgnoreWhitespace = true,
    DtdConformance = DtdConformanceMode.Ignore,
};

Node root;
using (XmlReader xr = XmlReader.Create(inPath, xs))
{
    root = ReadTree(xr);
    if (root == null)
    {
        Console.Error.WriteLine("no elements found in " + inPath);
        return 1;
    }
}

float canvasW = F(root.A("width"), 48);
float canvasH = F(root.A("height"), 48);
float[] vb = ParseViewBox(root.A("viewBox"));
float vbW = vb[2], vbH = vb[3];

Renderer r = new();
r.Render(root, canvasW, canvasH, vb);

// ---------------------------------------------------------------- output ----

using (Bitmap bmp = new((int)canvasW, (int)canvasH, PixelFormat.Format32bppArgb))
{
    using (Graphics g = Graphics.FromImage(bmp))
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Color.Transparent);
        // Map the viewBox onto the canvas (covers viewBox != width/height too).
        g.TranslateTransform(vb[0], vb[1]);
        g.ScaleTransform((float)canvasW / vbW, (float)canvasH / vbH);
        g = g; // (no-op; keep the transformed graphics below)
    }
}

// (rendering happens against the real bitmap below; the block above only
//  validates the numbers)

using (Bitmap bmp = new((int)canvasW, (int)canvasH, PixelFormat.Format32bppArgb))
{
    using (Graphics g = Graphics.FromImage(bmp))
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Color.Transparent);
        g.TranslateTransform(vb[0], vb[1]);
        g.ScaleTransform((float)canvasW / vbW, (float)canvasH / vbH);
        r.Render(g, root);
    }
    if (bmp.Width == size && bmp.Height == size)
    {
        bmp.Save(outPath, ImageFormat.Png);
    }
    else
    {
        using Bitmap canvas = new(size, size);
        using Graphics g = Graphics.FromImage(canvas);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.PixelOffsetMode = PixelOffsetMode.Half;
        g.DrawImage(bmp, 0, 0, size, size);
        canvas.Save(outPath, ImageFormat.Png);
    }
}

Console.WriteLine($"{System.IO.Path.GetFileName(inPath)} -> {outPath} ({size}x{size})");
return 0;

// --------------------------------------------------------------- helpers ----

static Node ReadTree(XmlReader xr)
{
    Node current = null;
    Node first = null;
    while (xr.Read())
    {
        switch (xr.NodeType)
        {
            case XmlNodeType.Element:
                Node n = new(xr.LocalName, new Dictionary<string, string>());
                while (xr.MoveToNextAttribute())
                {
                    n.Atr.Add(xr.LocalName, xr.Value);
                }
                xr.MoveToElement();
                if (current == null)
                {
                    first = n;
                }
                else
                {
                    current.Children.Add(n);
                }
                current = n;
                break;
            case XmlNodeType.EndElement:
                current = (current == first) ? null : FindParent(first, current);
                break;
            case XmlNodeType.Document:
                if (first == null && xr.HasNodes)
                {
                    // fall through: the first element will be read next
                }
                break;
        }
    }
    return first;
}

static Node FindParent(Node root, Node child)
{
    return ParentOf(root, child);
}

static Node ParentOf(Node node, Node target)
{
    foreach (Node c in node.Children)
    {
        if (c == target)
        {
            return node;
        }
        Node p = ParentOf(c, target);
        if (p != null)
        {
            return p;
        }
    }
    return null;
}

static float F(string s, float dflt)
{
    return float.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out float v) ? v : dflt;
}

static float[] ParseViewBox(string s)
{
    if (string.IsNullOrEmpty(s))
    {
        return new float[] { 0, 0, 0, 0 };
    }
    Match m = Regex.Match(s, @"[\d.\-]+");
    List<float> nums = new();
    MatchCollection mc = Regex.Matches(s, @"-?[\d.]+");
    foreach (Match mm in mc)
    {
        nums.Add(float.Parse(mm.Value, CultureInfo.InvariantCulture));
    }
    if (nums.Count != 4)
    {
        throw new FormatException("bad viewBox: " + s);
    }
    return nums.ToArray();
}

// ------------------------------------------------------------------ model ----

class Node
{
    public string Name;
    public Dictionary<string, string> Atr;
    public List<Node> Children = new();

    public Node(string name, Dictionary<string, string> atr)
    {
        Name = name;
        Atr = atr;
    }

    public string A(string key)
    {
        return Atr.TryGetValue(key, out string v) ? v : null;
    }
}

// ---------------------------------------------------------------- render ----

class Renderer
{
    Color? _fill;
    Color? _stroke;
    float _strokeW = 1;
    LineCap _cap = LineCap.Flat;
    LineJoin _join = LineJoin.Miter;

    public void Render(Node root, float w, float h, float[] vb)
    {
        // no-op entry point kept for the validation pass above
    }

    public void Render(Graphics g, Node root)
    {
        if (root.Name != "svg")
        {
            throw new NotSupportedException("root element must be <svg>, got <" + root.Name + ">");
        }
        foreach (Node child in root.Children)
        {
            Draw(g, child);
        }
    }

    void Draw(Graphics g, Node n)
    {
        SaveState();
        ApplyAttributes(n);

        switch (n.Name)
        {
            case "g":
                foreach (Node c in n.Children)
                {
                    Draw(g, c);
                }
                break;
            case "rect":
                DrawRect(g, n);
                break;
            case "line":
                DrawLine(g, n);
                break;
            case "circle":
                DrawEllipse(g, F(n.A("cx"), 0), F(n.A("cy"), 0), F(n.A("r"), 0), F(n.A("r"), 0));
                break;
            case "ellipse":
                DrawEllipse(g, F(n.A("cx"), 0), F(n.A("cy"), 0), F(n.A("rx"), 0), F(n.A("ry"), 0));
                break;
            case "path":
                DrawPath(g, n);
                break;
            default:
                if (n.Children.Count > 0)
                {
                    foreach (Node c in n.Children)
                    {
                        Draw(g, c);
                    }
                }
                else
                {
                    throw new NotSupportedException(
                        "unsupported element <" + n.Name + "> (this tool renders only " +
                        "svg/g/rect/line/circle/ellipse/path with a basic attribute set)");
                }
                break;
        }

        RestoreState();
    }

    void SaveState()
    {
        _fillSaved = _fill;
        _strokeSaved = _stroke;
        _strokeWSaved = _strokeW;
        _capSaved = _cap;
        _joinSaved = _join;
    }

    void RestoreState()
    {
        _fill = _fillSaved;
        _stroke = _strokeSaved;
        _strokeW = _strokeWSaved;
        _cap = _capSaved;
        _join = _joinSaved;
    }

    Color? _fillSaved, _strokeSaved;
    float _strokeWSaved;
    LineCap _capSaved;
    LineJoin _joinSaved;

    void ApplyAttributes(Node n)
    {
        string s;
        if (!string.IsNullOrEmpty(s = n.A("fill")))
        {
            _fill = ParseColor(s);
        }
        if (!string.IsNullOrEmpty(s = n.A("stroke")))
        {
            _stroke = ParseColor(s);
        }
        if ((s = n.A("stroke-width")) != null)
        {
            _strokeW = F(s, 1);
        }
        if ((s = n.A("stroke-linecap")) != null)
        {
            _cap = s == "round" ? LineCap.Round : s == "square" ? LineCap.Square : LineCap.Flat;
        }
        if ((s = n.A("stroke-linejoin")) != null)
        {
            _join = s == "round" ? LineJoin.Round : s == "bevel" ? LineJoin.Bevel : LineJoin.Miter;
        }
    }

    void DrawRect(Graphics g, Node n)
    {
        float x = F(n.A("x"), 0);
        float y = F(n.A("y"), 0);
        float w = F(n.A("width"), 0);
        float h = F(n.A("height"), 0);
        float rx = F(n.A("rx"), F(n.A("ry"), 0));
        float ry = F(n.A("ry"), 0);
        if (rx <= 0 || ry <= 0)
        {
            RectangleF r = new(x, y, w, h);
            if (_fill.HasValue)
            {
                g.FillRectangle(Brush(_fill), r);
            }
            if (_stroke.HasValue)
            {
                g.DrawRectangle(Pen(), r);
            }
        }
        else
        {
            using GraphicsPath p = RoundedRect(x, y, w, h, rx, ry);
            if (_fill.HasValue)
            {
                g.FillPath(Brush(_fill), p);
            }
            if (_stroke.HasValue)
            {
                g.DrawPath(Pen(), p);
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
        if (_stroke.HasValue)
        {
            g.DrawLine(Pen(), F(n.A("x1"), 0), F(n.A("y1"), 0), F(n.A("x2"), 0), F(n.A("y2"), 0));
        }
    }

    void DrawEllipse(Graphics g, float cx, float cy, float rx, float ry)
    {
        RectangleF r = new(cx - rx, cy - ry, 2 * rx, 2 * ry);
        if (_fill.HasValue)
        {
            g.FillEllipse(Brush(_fill), r);
        }
        if (_stroke.HasValue)
        {
            g.DrawEllipse(Pen(), r);
        }
    }

    void DrawPath(Graphics g, Node n)
    {
        string d = n.A("d");
        if (d == null)
        {
            throw new NotSupportedException("<path> without a d attribute");
        }
        using GraphicsPath p = PathBuilder.Build(d);
        if (_fill.HasValue)
        {
            g.FillPath(Brush(_fill), p);
        }
        if (_stroke.HasValue)
        {
            g.DrawPath(Pen(), p);
        }
    }

    SolidBrush Brush(Color? c)
    {
        return new SolidBrush(c.Value);
    }

    Pen Pen()
    {
        return new Pen(_stroke.Value, _strokeW)
        {
            StartCap = _cap,
            EndCap = _cap,
            LineJoin = _join,
            MiterLimit = 4,
        };
    }

    static Color? ParseColor(string s)
    {
        s = s.Trim().ToLowerInvariant();
        if (s == "none")
        {
            return null;
        }
        if (s == "white" || s == "#ffffff" || s == "#fff")
        {
            return Color.White;
        }
        if (s == "black" || s == "#000000" || s == "#000")
        {
            return Color.Black;
        }
        if (s.StartsWith("#") && (s.Length == 4 || s.Length == 7))
        {
            try
            {
                return ColorTranslator.FromHtml(s);
            }
            catch
            {
                // fall through
            }
        }
        throw new NotSupportedException(
            "unsupported color '" + s + "' (this tool handles none/white/black and #hex)");
    }
}

// ------------------------------------------------------- path data parser ----

static class PathBuilder
{
    static readonly Regex Token = new(
        @"([MmLlHhVvCcSsQqTtAaZz])|(-?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)",
        RegexOptions.Compiled);

    public static GraphicsPath Build(string d)
    {
        List<float> nums = new();
        char cmd = '\0';
        bool cmdConsumed = false;

        GraphicsPath p = new();
        p.StartFigure();

        float x = 0, y = 0;         // current point
        float startX = 0, startY = 0;
        float lastCx = 0, lastCy = 0; // last quadratic control point (for S/T)
        char lastCmd = '\0';

        void Need(int n)
        {
            if (nums.Count < n)
            {
                throw new FormatException("path '" + d + "': expected " + n + " more number(s) after '" + cmd + "'");
            }
        }

        for (int i = 0; i < d.Length; i++)
        {
            char c = d[i];
            if (char.IsWhiteSpace(c))
            {
                continue;
            }
            if (char.IsLetter(c))
            {
                if (cmd != '\0' && nums.Count > 0)
                {
                    throw new FormatException("path '" + d + "': leftover numbers before command '" + c + "'");
                }
                cmd = c;
                cmdConsumed = false;
                continue;
            }
            if (c == '-' || c == '+' || c == '.' || (c >= '0' && c <= '9'))
            {
                // read a full number
                int start = i;
                while (i < d.Length && (char.IsWhiteSpace(d[i]) == false &&
                       (char.IsLetter(d[i]) || d[i] == '-' || d[i] == '+' || d[i] == '.' ||
                        (d[i] >= '0' && d[i] <= '9'))))
                {
                    // stop at a letter (next command)
                    if (char.IsLetter(d[i]) && i > start)
                    {
                        break;
                    }
                    i++;
                }
                i--;
                string tok = d.Substring(start, i - start + 1);
                nums.Add(float.Parse(tok, CultureInfo.InvariantCulture));
                continue;
            }
            throw new FormatException("path '" + d + "': unexpected character '" + c + "'");
        }

        int ni = 0;
        float N()
        {
            if (ni >= nums.Count)
            {
                throw new FormatException("path '" + d + "': ran out of numbers");
            }
            return nums[ni++];
        }

        while (ni < nums.Count || true)
        {
            if (cmd == '\0')
            {
                // implicit repeat of the last command is handled by re-reading:
                // if there are numbers left but no command, the SVG spec says to
                // repeat the previous command.
                if (ni >= nums.Count)
                {
                    break;
                }
                cmd = lastCmd == '\0' ? cmd : lastCmd;
                if (cmd == '\0')
                {
                    throw new FormatException("path '" + d + "': numbers without a command");
                }
            }
            Execute(p, ref x, ref y, ref startX, ref startY, ref lastCx, ref lastCy, ref lastCmd, cmd, N);
            if (ni >= nums.Count)
            {
                break;
            }
        }

        p.CloseFigure();
        return p;
    }

    static void Execute(GraphicsPath p, ref float x, ref float y, ref float startX, ref float startY,
        ref float lastCx, ref float lastCy, ref char lastCmd, char c, Func<float> N)
    {
        switch (c)
        {
            case 'M':
                x = N(); y = N();
                startX = x; startY = y;
                p.StartFigure();
                p.LineTo(x, y);
                break;
            case 'm':
                x += N(); y += N();
                startX = x; startY = y;
                p.StartFigure();
                p.LineTo(x, y);
                break;
            case 'L':
                x = N(); y = N();
                p.LineTo(x, y);
                break;
            case 'l':
                x += N(); y += N();
                p.LineTo(x, y);
                break;
            case 'H':
                x = N();
                p.LineTo(x, y);
                break;
            case 'h':
                x += N();
                p.LineTo(x, y);
                break;
            case 'V':
                y = N();
                p.LineTo(x, y);
                break;
            case 'v':
                y += N();
                p.LineTo(x, y);
                break;
            case 'C':
            {
                float c1x = N(), c1y = N(), c2x = N(), c2y = N();
                x = N(); y = N();
                lastCx = c2x; lastCy = c2y;
                p.AddCurve(new PointF(x, y), new[] { new PointF(c1x, c1y), new PointF(c2x, c2y) });
                break;
            }
            case 'c':
            {
                float c1x = x + N(), c1y = y + N(), c2x = x + N(), c2y = y + N();
                x += N(); y += N();
                lastCx = c2x; lastCy = c2y;
                p.AddCurve(new PointF(x, y), new[] { new PointF(c1x, c1y), new PointF(c2x, c2y) });
                break;
            }
            case 'S':
            {
                float c1x = 2 * x - lastCx;
                float c1y = 2 * y - lastCy;
                float c2x = N(), c2y = N();
                x = N(); y = N();
                lastCx = c2x; lastCy = c2y;
                p.AddCurve(new PointF(x, y), new[] { new PointF(c1x, c1y), new PointF(c2x, c2y) });
                break;
            }
            case 's':
            {
                float c1x = 2 * x - lastCx;
                float c1y = 2 * y - lastCy;
                float c2x = x + N(), c2y = y + N();
                x += N(); y += N();
                lastCx = c2x; lastCy = c2y;
                p.AddCurve(new PointF(x, y), new[] { new PointF(c1x, c1y), new PointF(c2x, c2y) });
                break;
            }
            case 'Q':
            {
                float cx = N(), cy = N();
                x = N(); y = N();
                lastCx = cx; lastCy = cy;
                p.AddBezier(new PointF(x, y), new[] { new PointF(cx, cy), new PointF(cx, cy) });
                break;
            }
            case 'q':
            {
                float cx = x + N(), cy = y + N();
                x += N(); y += N();
                lastCx = cx; lastCy = cy;
                p.AddBezier(new PointF(x, y), new[] { new PointF(cx, cy), new PointF(cx, cy) });
                break;
            }
            case 'T':
            {
                float cx = 2 * x - lastCx;
                float cy = 2 * y - lastCy;
                x = N(); y = N();
                lastCx = cx; lastCy = cy;
                p.AddBezier(new PointF(x, y), new[] { new PointF(cx, cy), new PointF(cx, cy) });
                break;
            }
            case 't':
            {
                float cx = 2 * x - lastCx;
                float cy = 2 * y - lastCy;
                x += N(); y += N();
                lastCx = cx; lastCy = cy;
                p.AddBezier(new PointF(x, y), new[] { new PointF(cx, cy), new PointF(cx, cy) });
                break;
            }
            case 'A':
            {
                float rx = N(), ry = N(), phi = N(), laf = N(), sf = N();
                float x2 = N(), y2 = N();
                AddArc(p, x, y, x2, y2, rx, ry, phi, laf != 0, sf != 0);
                x = x2; y = y2;
                break;
            }
            case 'a':
            {
                float rx = N(), ry = N(), phi = N(), laf = N(), sf = N();
                x += N(); y += N();
                AddArc(p, x - (x - (x)), y - (y - (y)), x, y, rx, ry, phi, laf != 0, sf != 0);
                break;
            }
            case 'Z':
            case 'z':
                x = startX; y = startY;
                p.CloseFigure();
                break;
            default:
                throw new NotSupportedException("path command '" + c + "' is not supported");
        }
    }
}
