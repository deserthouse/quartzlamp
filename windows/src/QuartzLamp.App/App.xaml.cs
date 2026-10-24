using Microsoft.UI.Xaml;

namespace QuartzLamp.App;

/// <summary>WinUI 3 模板验证应用（P0-a）：最小窗口，仅证明工具链可构建可运行。</summary>
public partial class App : Application
{
    private Window? _window;

    public App()
    {
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        _window = new MainWindow();
        _window.Activate();
    }
}
