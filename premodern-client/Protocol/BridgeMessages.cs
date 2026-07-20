#nullable enable

using System.Collections.Generic;

namespace PremodernClient.Protocol;

public static class BridgeSchema
{
    public const int SupportedVersion = 1;
}

public abstract record BridgeMessage(int SchemaVersion, string Type);

public sealed record LifecycleMessage(int SchemaVersion, string Event, string? Detail)
    : BridgeMessage(SchemaVersion, "lifecycle");

public sealed record ControllerMessage(
    int SchemaVersion,
    int? PlayerId,
    string? PlayerName,
    string? ControllerInterface,
    string? Implementation)
    : BridgeMessage(SchemaVersion, "controller");

public sealed record StateMessage(
    int SchemaVersion,
    string? Source,
    long StateSequence,
    int Turn,
    string? Phase,
    int? ActivePlayerId,
    int? PriorityPlayerId,
    IReadOnlyList<PlayerSnapshot> Players,
    IReadOnlyList<StackSnapshot> Stack)
    : BridgeMessage(SchemaVersion, "state");

public sealed record PlayerSnapshot(
    int Id,
    string? Name,
    int Life,
    bool HasPriority,
    IReadOnlyList<CardSnapshot> HandVisible,
    IReadOnlyList<CardSnapshot> Battlefield,
    IReadOnlyList<CardSnapshot> Graveyard);

public sealed record CardSnapshot(
    int Id,
    string? Name,
    string? Zone,
    bool Hidden,
    bool Tapped);

public sealed record StackSnapshot(
    int Id,
    string? Text,
    CardSnapshot? Source,
    IReadOnlyList<CardSnapshot> Targets);

public sealed record InteractionMessage(
    int SchemaVersion,
    long InteractionSequence,
    string? Reason,
    string? Prompt,
    IReadOnlyList<int> WeaklySelectableCardIds,
    IReadOnlyList<int> SelectableCardIds,
    IReadOnlyList<int> SelectablePlayerIds,
    int Min,
    int Max,
    ButtonSnapshot Buttons)
    : BridgeMessage(SchemaVersion, "interaction");

public sealed record ButtonSnapshot(
    string? OkLabel,
    string? CancelLabel,
    bool OkEnabled,
    bool CancelEnabled,
    bool FocusOk);

public sealed record QueryMessage(
    int SchemaVersion,
    string? RequestId,
    string? Kind,
    int? HostCardId,
    string? HostCardName,
    IReadOnlyList<QueryChoice> Choices,
    string? Offered,
    bool? Cancellable)
    : BridgeMessage(SchemaVersion, "query");

public sealed record QueryChoice(int Id, string? Description, bool CanPlay);

public sealed record ErrorMessage(
    int SchemaVersion,
    string? Code,
    string? Message,
    string? RequestId,
    long? ReceivedInteractionSequence,
    long? CurrentInteractionSequence,
    long? ReceivedSchemaVersion,
    int? SupportedSchemaVersion)
    : BridgeMessage(SchemaVersion, "error");

public sealed record ActionAcceptedMessage(
    int SchemaVersion,
    string? Action,
    int? SelectedId,
    long InteractionSequence)
    : BridgeMessage(SchemaVersion, "actionAccepted");

public sealed record UnknownBridgeMessage(int SchemaVersion, string UnknownType, string RawJson)
    : BridgeMessage(SchemaVersion, UnknownType);

public sealed record ProtocolProblemMessage(string Code, string Detail, string RawJson)
    : BridgeMessage(0, "protocolProblem");
