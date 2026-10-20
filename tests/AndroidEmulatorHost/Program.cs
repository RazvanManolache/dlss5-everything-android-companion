using System.Net;
using System.Text.Json;
using Dlss5CompatApp;

// Explicit opt-in loopback host for emulator instrumentation. Never shipped.
if (args.Length != 2 || args[0] is not ("pattern" or "phoenix" or "codec" or "pc-face" or "phone-receiver"))
    throw new ArgumentException("Usage: AndroidEmulatorHost pattern|phoenix|codec|pc-face|phone-receiver PRIVATE_TEST_DIRECTORY");
var root = Path.GetFullPath(args[1]);
Directory.CreateDirectory(root);
AppDomain.CurrentDomain.FirstChanceException += (_, e) => {
    if (e.Exception is System.Security.Authentication.AuthenticationException ||
        e.Exception.StackTrace?.Contains("LanFrameServer.", StringComparison.Ordinal) == true)
        Console.Error.WriteLine(e.Exception.GetType().Name + ": " + e.Exception.Message);
};
using var lifetime = new CancellationTokenSource(TimeSpan.FromMinutes(20));
if (args[0] == "phone-receiver") {
    var package = Path.GetFullPath(Environment.GetEnvironmentVariable("PHOENIX_TEST_PACKAGE_ROOT") ?? throw new InvalidOperationException("Package required"));
    var ip = IPAddress.Parse(Environment.GetEnvironmentVariable("PHOENIX_PHONE_LISTEN_ADDRESS") ?? throw new InvalidOperationException("LAN address required"));
    var payload = PayloadScanner.Scan(Path.Combine(package,"Payload"));
    var model = Directory.EnumerateFiles(Path.Combine(package,"Payload"),"nvngx_dlssnr.dll",SearchOption.AllDirectories).First();
    var phonePairing = Path.Combine(root,"pairing.json");
    Exception? failure = null;
    var thread = new Thread(() => {
        try {
            Application.EnableVisualStyles();
            using var form = new Form { Text="DLSS5 Everything - Phone receiver", Width=900, Height=650, StartPosition=FormStartPosition.CenterScreen };
            var view = new LanFrameView(() => payload, () => model);
            form.Controls.Add(view);
            form.Shown += (_, _) => {
                try {
                    const System.Reflection.BindingFlags flags = System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic;
                    var type = typeof(LanFrameView);
                    var address = (ComboBox)type.GetField("address", flags)!.GetValue(view)!;
                    address.SelectedItem = ip;
                    type.GetMethod("StartServer", flags)!.Invoke(view,null);
                    var server = (LanFrameServer?)type.GetField("server",flags)!.GetValue(view) ?? throw new IOException("Receiver did not start");
                    File.WriteAllText(phonePairing,JsonSerializer.Serialize(new { host=ip.ToString(),port=47855,fingerprint=server.Fingerprint,token=server.PairingToken,
                        codec="raw",fps=5,maxEdge=4096,touchThrough=true,opacity=1,captureMode="follow" }));
                } catch(Exception error) { failure=error;form.Close(); }
            };
            Application.Run(form);
        } catch(Exception error) { failure=error; }
        finally { File.Delete(phonePairing); }
    });
    thread.SetApartmentState(ApartmentState.STA); thread.Start(); thread.Join();
    if(failure is not null)throw new IOException("Phone receiver failed",failure);
    return;
}
if (args[0] == "pc-face") {
    var package = Path.GetFullPath(Environment.GetEnvironmentVariable("PHOENIX_TEST_PACKAGE_ROOT") ?? throw new InvalidOperationException("Package required"));
    var input = Path.GetFullPath(Environment.GetEnvironmentVariable("PHOENIX_TEST_INPUT") ?? throw new InvalidOperationException("Input required"));
    var payload = PayloadScanner.Scan(Path.Combine(package, "Payload"));
    var model = Directory.EnumerateFiles(Path.Combine(package,"Payload"),"nvngx_dlssnr.dll",SearchOption.AllDirectories).First();
    var settings = new ScreenStudioSettings { Scale=1,Iterations=1,Enabled=true,Residual=false,Cursor=false };
    await ScreenMediaConverter.ConvertAsync(new(input,Path.Combine(root,"pc-phoenix.png")),settings,payload,model,token:lifetime.Token);
    File.Copy(input,Path.Combine(root,"pc-original.png"),false);
    Console.WriteLine("PC Screen Studio native-resolution face conversion completed.");
    return;
}
if (args[0] == "codec") {
    foreach (var (w, h) in new[] { (257, 255), (1280, 720) }) {
        using var codec = await PyrowaveFrameCodec.CreateAsync("pyrowave", w, h, 100, 15, lifetime.Token);
        var pixels = new byte[w * h * 4];
        for (var y = 0; y < h; y++) for (var x = 0; x < w; x++) {
            var i = 4 * (y * w + x); pixels[i] = (byte)(x * 255 / w); pixels[i + 1] = (byte)(y * 255 / h); pixels[i + 2] = 80; pixels[i + 3] = 255;
        }
        var encoded = await codec.EncodeAsync(pixels, lifetime.Token);
        var decoded = await codec.DecodeAsync(encoded, lifetime.Token);
        double error = 0; for (var i = 0; i < pixels.Length; i++) if (i % 4 != 3) error += Math.Abs((int)pixels[i] - decoded[i]);
        error /= w * h * 3;
        if (error > 12) throw new InvalidDataException("Codec error exceeds expected limit: " + error);
        Console.WriteLine(JsonSerializer.Serialize(new { Width = w, Height = h, EncodedBytes = encoded.Length, MeanAbsoluteError = error }));
    }
    return;
}
using var server = new LanFrameServer(async (w, h, settings, ct) => {
    if (args[0] == "pattern") return new PatternProcessor(settings);
    var package = Environment.GetEnvironmentVariable("PHOENIX_TEST_PACKAGE_ROOT")
        ?? throw new InvalidOperationException("Set PHOENIX_TEST_PACKAGE_ROOT to a local compatible package.");
    var payload = PayloadScanner.Scan(Path.Combine(package, "Payload"));
    var model = Directory.EnumerateFiles(Path.Combine(package, "Payload"), "nvngx_dlssnr.dll", SearchOption.AllDirectories).First();
    var processor = new LanPhoenixBackend();
    try { await processor.StartAsync(payload, model, w, h, settings, ct); return processor; }
    catch { processor.Dispose(); throw; }
});
server.Start(IPAddress.Loopback, 47855);
var pairing = Path.Combine(root, "pairing.json");
try {
    await File.WriteAllTextAsync(pairing, JsonSerializer.Serialize(new {
        host = "10.0.2.2", port = 47855, fingerprint = server.Fingerprint, token = server.PairingToken,
        codec = "raw", fps = 5, maxEdge = 720, touchThrough = true, opacity = .8
    }));
    Console.WriteLine("Loopback emulator host ready: " + args[0]);
    while (!lifetime.IsCancellationRequested && !File.Exists(Path.Combine(root, "stop"))) {
        await File.WriteAllTextAsync(Path.Combine(root, "status.json"), JsonSerializer.Serialize(server.Status));
        await Task.Delay(200, lifetime.Token);
    }
} catch (OperationCanceledException) when (lifetime.IsCancellationRequested) { }
finally { File.Delete(pairing); await server.StopAsync(); }

sealed class PatternProcessor(LanFrameSettings settings) : ILanFrameProcessor {
    public Task ApplyAsync(LanFrameSettings next, CancellationToken token) { settings = next; return Task.CompletedTask; }
    public Task<byte[]> EvaluateAsync(ReadOnlyMemory<byte> rgba, bool reset, CancellationToken token) {
        token.ThrowIfCancellationRequested(); var result = rgba.ToArray();
        if (settings.Enabled) for (var i = 0; i < result.Length; i += 4) {
            result[i] = (byte)(255 - result[i]); result[i + 1] = (byte)(255 - result[i + 1]); result[i + 2] = (byte)(255 - result[i + 2]);
        }
        return Task.FromResult(result);
    }
    public void Dispose() { }
}
