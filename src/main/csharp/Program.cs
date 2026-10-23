using System;
using System.Globalization;
using System.IO;
using System.Text;

// Заставка обновления — отдельный exe, который ставится вместе с лаунчером. Лаунчер копирует его в кэш
// (файлы в папке установки заменяет msiexec) и запускает с путём к job.txt, а сам закрывается.
internal static class Program
{
    private const int JobLines = 8;

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length == 6 && args[0] == "--render")
        {
            float scale = float.Parse(args[5], CultureInfo.InvariantCulture);
            JuxUpdateSplash.Render(args[1], args[2], args[3], args[4], scale, false);
            return 0;
        }
        if (args.Length != 1) return 2;

        string[] job;
        try
        {
            job = File.ReadAllLines(args[0], new UTF8Encoding(false));
        }
        catch (Exception)
        {
            return 2;
        }
        if (job.Length < JobLines) return 2;

        int launcher;
        if (!int.TryParse(job[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out launcher)) return 2;
        string assets = Path.GetDirectoryName(Path.GetFullPath(args[0]));
        return JuxUpdateSplash.Run(launcher, job[1], job[2], job[3], assets, job[4], job[5], job[6], job[7]);
    }
}
