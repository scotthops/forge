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

	private Control visualRoot = null!;
	private TextureRect cardImage = null!;
	private PanelContainer fallback = null!;
	private Label fallbackName = null!;
	private Panel actionableFrame = null!;
	private Panel selectedFrame = null!;
	private Panel attackingFrame = null!;
	private Panel blockingFrame = null!;
	private PanelContainer attackingBadge = null!;
	private PanelContainer instanceBadge = null!;
	private Label instanceLabel = null!;
	private PanelContainer relationshipBadge = null!;
	private Label relationshipLabel = null!;
	private string displayName = string.Empty;
	private Texture2D? texture;
	private bool primaryPressStarted;
	private bool primaryActionEnabled;
	private bool tappedLayoutEnabled;
	private string instanceText = string.Empty;
	private string relationshipText = string.Empty;

	public bool Actionable { get; private set; }
	public bool Selected { get; private set; }
	public bool Attacking { get; private set; }
	public bool Blocking { get; private set; }
	public bool Tapped { get; private set; }

	public override void _Ready()
	{
		visualRoot = GetNode<Control>("%VisualRoot");
		cardImage = GetNode<TextureRect>("%CardImage");
		fallback = GetNode<PanelContainer>("%Fallback");
		fallbackName = GetNode<Label>("%FallbackName");
		actionableFrame = GetNode<Panel>("%ActionableFrame");
		selectedFrame = GetNode<Panel>("%SelectedFrame");
		attackingFrame = GetNode<Panel>("%AttackingFrame");
		blockingFrame = GetNode<Panel>("%BlockingFrame");
		attackingBadge = GetNode<PanelContainer>("%AttackingBadge");
		instanceBadge = GetNode<PanelContainer>("%InstanceBadge");
		instanceLabel = GetNode<Label>("%InstanceLabel");
		relationshipBadge = GetNode<PanelContainer>("%RelationshipBadge");
		relationshipLabel = GetNode<Label>("%RelationshipLabel");
		MouseExited += () => primaryPressStarted = false;
		Resized += RefreshVisualState;
		RefreshDisplay();
	}

	public void Configure(string visibleName, Texture2D? cardTexture, string tooltipText)
	{
		displayName = visibleName;
		texture = cardTexture;
		TooltipText = tooltipText;

		if (IsNodeReady())
		{
			RefreshDisplay();
		}
	}

	public void SetActionable(bool actionable)
	{
		Actionable = actionable;
		if (IsNodeReady())
		{
			RefreshVisualState();
		}
	}

	public void SetPrimaryActionEnabled(bool enabled)
	{
		primaryActionEnabled = enabled;
	}

	public void SetSelected(bool selected)
	{
		Selected = selected;
		if (IsNodeReady())
		{
			RefreshVisualState();
		}
	}

	public void SetAttacking(bool attacking)
	{
		Attacking = attacking;
		if (IsNodeReady())
		{
			RefreshVisualState();
		}
	}

	public void SetBlocking(bool blocking)
	{
		Blocking = blocking;
		if (IsNodeReady())
		{
			RefreshVisualState();
		}
	}

	public void SetRelationshipText(string text)
	{
		relationshipText = text;
		if (IsNodeReady())
		{
			RefreshVisualState();
		}
	}

	public void SetInstanceBadge(string text)
	{
		instanceText = text;
		if (IsNodeReady())
		{
			RefreshVisualState();
		}
	}

	public void SetTapped(bool tapped)
	{
		tappedLayoutEnabled = true;
		Tapped = tapped;
		if (IsNodeReady())
		{
			RefreshVisualState();
		}
	}

	public override void _GuiInput(InputEvent inputEvent)
	{
		if (inputEvent is not InputEventMouseButton mouseButton)
		{
			return;
		}

		bool pointerOverVisual = IsPointerOverVisual();
		if (mouseButton.ButtonIndex == MouseButton.Right)
		{
			if (!pointerOverVisual)
			{
				return;
			}
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

		if (mouseButton.Pressed)
		{
			primaryPressStarted = primaryActionEnabled && pointerOverVisual;
			if (pointerOverVisual)
			{
				AcceptEvent();
			}
			return;
		}

		bool requestAction = primaryPressStarted && primaryActionEnabled && pointerOverVisual;
		primaryPressStarted = false;
		if (!pointerOverVisual)
		{
			return;
		}
		AcceptEvent();
		if (requestAction)
		{
			EmitSignal(SignalName.PrimaryActionRequested);
		}
	}

	private void RefreshDisplay()
	{
		cardImage.Texture = texture;
		cardImage.Visible = texture != null;
		fallback.Visible = texture == null;
		fallbackName.Text = displayName;
		RefreshVisualState();
	}

	private void RefreshVisualState()
	{
		Vector2 slotMinimum = tappedLayoutEnabled
			? new Vector2(MiniatureSize.Y, MiniatureSize.Y)
			: MiniatureSize;
		CustomMinimumSize = slotMinimum;

		Vector2 slotSize = new(
			Mathf.Max(Size.X, slotMinimum.X),
			Mathf.Max(Size.Y, slotMinimum.Y));
		visualRoot.Size = MiniatureSize;
		visualRoot.Position = (slotSize - MiniatureSize) / 2f;
		visualRoot.PivotOffset = MiniatureSize / 2f;
		visualRoot.RotationDegrees = Tapped ? 90f : 0f;
		actionableFrame.Visible = Actionable;
		selectedFrame.Visible = Selected;
		attackingFrame.Visible = Attacking;
		blockingFrame.Visible = Blocking;
		attackingBadge.Visible = Attacking;
		instanceLabel.Text = instanceText;
		instanceBadge.Visible = !string.IsNullOrWhiteSpace(instanceText);
		relationshipLabel.Text = relationshipText;
		relationshipBadge.Visible = !string.IsNullOrWhiteSpace(relationshipText);
	}

	private bool IsPointerOverVisual()
	{
		return new Rect2(Vector2.Zero, visualRoot.Size)
			.HasPoint(visualRoot.GetLocalMousePosition());
	}
}
