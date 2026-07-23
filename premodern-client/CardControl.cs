#nullable enable

using Godot;

namespace PremodernClient;

public partial class CardControl : Control
{
	[Signal]
	public delegate void PrimaryActionRequestedEventHandler();

	[Signal]
	public delegate void PreviewRequestedEventHandler();

	public static readonly Vector2 DefaultMiniatureSize = new(112, 156);

	[Export]
	public Vector2 MiniatureSize { get; set; } = DefaultMiniatureSize;

	private TextureRect cardImage = null!;
	private PanelContainer fallback = null!;
	private Label fallbackName = null!;
	private string displayName = string.Empty;
	private Texture2D? texture;
	private bool primaryPressStarted;

	public bool Actionable { get; private set; }

	public override void _Ready()
	{
		cardImage = GetNode<TextureRect>("%CardImage");
		fallback = GetNode<PanelContainer>("%Fallback");
		fallbackName = GetNode<Label>("%FallbackName");
		MouseExited += () => primaryPressStarted = false;
		RefreshDisplay();
	}

	public void Configure(string visibleName, Texture2D? cardTexture, bool actionable,
		string tooltipText)
	{
		displayName = visibleName;
		texture = cardTexture;
		Actionable = actionable;
		TooltipText = tooltipText;
		CustomMinimumSize = MiniatureSize;

		if (IsNodeReady())
		{
			RefreshDisplay();
		}
	}

	public override void _GuiInput(InputEvent inputEvent)
	{
		if (inputEvent is not InputEventMouseButton mouseButton)
		{
			return;
		}

		if (mouseButton.ButtonIndex == MouseButton.Right)
		{
			AcceptEvent();
			if (mouseButton.Pressed)
			{
				EmitSignal(SignalName.PreviewRequested);
			}
			return;
		}

		if (mouseButton.ButtonIndex != MouseButton.Left)
		{
			return;
		}

		AcceptEvent();
		if (mouseButton.Pressed)
		{
			primaryPressStarted = Actionable;
			return;
		}

		bool requestAction = primaryPressStarted && Actionable;
		primaryPressStarted = false;
		if (requestAction)
		{
			EmitSignal(SignalName.PrimaryActionRequested);
		}
	}

	private void RefreshDisplay()
	{
		CustomMinimumSize = MiniatureSize;
		cardImage.Texture = texture;
		cardImage.Visible = texture != null;
		fallback.Visible = texture == null;
		fallbackName.Text = displayName;
	}
}
