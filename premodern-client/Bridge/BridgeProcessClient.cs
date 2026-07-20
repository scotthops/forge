#nullable enable

using PremodernClient.Protocol;
using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Threading.Channels;
using System.Threading.Tasks;

namespace PremodernClient.Bridge;

public sealed record BridgeLaunchOptions(
    string JavaExecutable,
    string BridgeJarPath,
    string Host,
    int Port,
    string Username,
    string AssetsDirectory,
    string WorkingDirectory);

public sealed class BridgeProcessClient : IDisposable
{
    private readonly ConcurrentQueue<BridgeMessage> messages = new();
    private readonly ConcurrentQueue<string> diagnostics = new();
    private readonly Channel<string> outboundLines = Channel.CreateUnbounded<string>(
        new UnboundedChannelOptions { SingleReader = true, SingleWriter = false });
    private readonly CancellationTokenSource cancellation = new();
    private Process? process;
    private Task? stdoutReader;
    private Task? stderrReader;
    private Task? stdinWriter;
    private bool disposed;

    public string Status { get; private set; } = "Stopped";
    public bool IsRunning => process is { HasExited: false };

    public void Start(BridgeLaunchOptions options)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        if (process != null)
        {
            throw new InvalidOperationException("The bridge process has already been started.");
        }
        if (!File.Exists(options.BridgeJarPath))
        {
            throw new FileNotFoundException("Forge bridge JAR was not found.", options.BridgeJarPath);
        }
        if (!Directory.Exists(options.AssetsDirectory))
        {
            throw new DirectoryNotFoundException($"Forge assets directory was not found: {options.AssetsDirectory}");
        }

        ProcessStartInfo startInfo = new(options.JavaExecutable)
        {
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            WorkingDirectory = options.WorkingDirectory
        };
        startInfo.ArgumentList.Add("-jar");
        startInfo.ArgumentList.Add(options.BridgeJarPath);
        startInfo.ArgumentList.Add("--host");
        startInfo.ArgumentList.Add(options.Host);
        startInfo.ArgumentList.Add("--port");
        startInfo.ArgumentList.Add(options.Port.ToString());
        startInfo.ArgumentList.Add("--username");
        startInfo.ArgumentList.Add(options.Username);
        startInfo.ArgumentList.Add("--assets-dir");
        startInfo.ArgumentList.Add(options.AssetsDirectory);

        process = new Process { StartInfo = startInfo, EnableRaisingEvents = true };
        process.Exited += ProcessExited;
        if (!process.Start())
        {
            process.Dispose();
            process = null;
            throw new InvalidOperationException("Java did not start the Forge bridge process.");
        }

        Status = $"Bridge running (PID {process.Id})";
        diagnostics.Enqueue($"Started bridge PID {process.Id}: {options.Host}:{options.Port}");
        stdoutReader = Task.Run(() => ReadStdoutAsync(process.StandardOutput, cancellation.Token));
        stderrReader = Task.Run(() => ReadStderrAsync(process.StandardError, cancellation.Token));
        stdinWriter = Task.Run(() => WriteStdinAsync(process.StandardInput, cancellation.Token));
    }

    public bool TrySend(BridgeCommand command, out string? error)
    {
        error = null;
        Process? activeProcess = process;
        if (disposed || activeProcess == null || activeProcess.HasExited)
        {
            error = "Forge bridge is not running.";
            return false;
        }

        string line;
        try
        {
            line = BridgeCommandSerializer.Serialize(command);
        }
        catch (Exception exception)
        {
            error = exception.Message;
            return false;
        }
        if (!outboundLines.Writer.TryWrite(line))
        {
            error = "Forge bridge command writer is closed.";
            return false;
        }
        diagnostics.Enqueue($"G2_OUTBOUND {line}");
        return true;
    }

    public bool TryDequeueMessage(out BridgeMessage? message)
    {
        return messages.TryDequeue(out message);
    }

    public bool TryDequeueDiagnostic(out string? diagnostic)
    {
        return diagnostics.TryDequeue(out diagnostic);
    }

    private async Task ReadStdoutAsync(StreamReader reader, CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                string? line = await reader.ReadLineAsync(token);
                if (line == null)
                {
                    break;
                }
                if (!string.IsNullOrWhiteSpace(line))
                {
                    messages.Enqueue(BridgeProtocolParser.Parse(line));
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception exception)
        {
            diagnostics.Enqueue($"Bridge stdout reader failed: {exception.Message}");
        }
    }

    private async Task ReadStderrAsync(StreamReader reader, CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                string? line = await reader.ReadLineAsync(token);
                if (line == null)
                {
                    break;
                }
                if (!string.IsNullOrWhiteSpace(line))
                {
                    diagnostics.Enqueue(line);
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception exception)
        {
            diagnostics.Enqueue($"Bridge stderr reader failed: {exception.Message}");
        }
    }

    private async Task WriteStdinAsync(StreamWriter writer, CancellationToken token)
    {
        try
        {
            await foreach (string line in outboundLines.Reader.ReadAllAsync(token))
            {
                await writer.WriteLineAsync(line.AsMemory(), token);
                await writer.FlushAsync(token);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception exception)
        {
            diagnostics.Enqueue($"Bridge stdin writer failed: {exception.Message}");
        }
    }

    private void ProcessExited(object? sender, EventArgs eventArgs)
    {
        Process? exitedProcess = process;
        string exit = exitedProcess == null ? "unknown" : exitedProcess.ExitCode.ToString();
        Status = $"Bridge exited (code {exit})";
        diagnostics.Enqueue(Status);
    }

    public void Dispose()
    {
        if (disposed)
        {
            return;
        }
        disposed = true;
        outboundLines.Writer.TryComplete();
        WaitForReader(stdinWriter);

        Process? activeProcess = process;
        if (activeProcess != null)
        {
            try
            {
                if (!activeProcess.HasExited)
                {
                    activeProcess.StandardInput.Close();
                    if (!activeProcess.WaitForExit(3000))
                    {
                        activeProcess.Kill(entireProcessTree: true);
                        activeProcess.WaitForExit(3000);
                    }
                }
            }
            catch (Exception exception)
            {
                diagnostics.Enqueue($"Bridge shutdown failed: {exception.Message}");
            }
        }

        cancellation.Cancel();
        WaitForReader(stdoutReader);
        WaitForReader(stderrReader);
        if (activeProcess != null)
        {
            activeProcess.Exited -= ProcessExited;
            activeProcess.Dispose();
        }
        cancellation.Dispose();
        process = null;
        Status = "Stopped";
    }

    private static void WaitForReader(Task? reader)
    {
        try
        {
            reader?.Wait(1000);
        }
        catch (AggregateException)
        {
        }
    }
}
