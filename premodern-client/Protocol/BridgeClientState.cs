#nullable enable

namespace PremodernClient.Protocol;

public sealed class BridgeClientState
{
    public string ConnectionStatus { get; private set; } = "Not started";
    public StateMessage? LatestState { get; private set; }
    public InteractionMessage? CurrentInteraction { get; private set; }
    public ControllerMessage? Controller { get; private set; }
    public QueryMessage? PendingQuery { get; private set; }
    public string? LastError { get; private set; }
    public string? LastNotice { get; private set; }
    public string? LastActionStatus { get; private set; }
    public AsyncInteractionCommand? PendingAsyncCommand { get; private set; }
    private bool pendingAsyncAccepted;

    public void Apply(BridgeMessage message)
    {
        switch (message)
        {
            case LifecycleMessage lifecycle:
                ConnectionStatus = string.IsNullOrWhiteSpace(lifecycle.Detail)
                    ? lifecycle.Event
                    : $"{lifecycle.Event}: {lifecycle.Detail}";
                break;
            case ControllerMessage controller:
                Controller = controller;
                break;
            case StateMessage state:
                LatestState = state;
                if (PendingAsyncCommand != null && pendingAsyncAccepted)
                {
                    PendingAsyncCommand = null;
                    pendingAsyncAccepted = false;
                    LastActionStatus = "Forge returned authoritative state after the accepted action.";
                }
                break;
            case InteractionMessage interaction:
                CurrentInteraction = interaction;
                if (PendingAsyncCommand != null
                    && interaction.InteractionSequence != PendingAsyncCommand.InteractionSequence)
                {
                    PendingAsyncCommand = null;
                    pendingAsyncAccepted = false;
                    LastActionStatus = "Forge advanced to a new interaction context.";
                }
                break;
            case QueryMessage query:
                PendingQuery = query;
                LastNotice = $"Pending human query {query.RequestId} ({query.Kind}).";
                break;
            case ErrorMessage error:
                LastError = $"{error.Code}: {error.Message}";
                if (error.RequestId == null)
                {
                    PendingAsyncCommand = null;
                    pendingAsyncAccepted = false;
                }
                if (error.Code == "STALE_INTERACTION")
                {
                    LastActionStatus = "Action rejected because the interaction changed. Choose again.";
                }
                if (error.RequestId != null && PendingQuery?.RequestId == error.RequestId)
                {
                    PendingQuery = null;
                }
                break;
            case ActionAcceptedMessage accepted:
                pendingAsyncAccepted = PendingAsyncCommand?.InteractionSequence
                    == accepted.InteractionSequence;
                LastActionStatus = $"Forge accepted {accepted.Action} at interaction {accepted.InteractionSequence}.";
                break;
            case UnknownBridgeMessage unknown:
                LastNotice = $"Unknown protocol message type: {unknown.UnknownType}";
                break;
            case ProtocolProblemMessage problem:
                LastError = $"{problem.Code}: {problem.Detail}";
                break;
        }
    }

    public void RecordCommandSent(BridgeCommand command)
    {
        LastError = null;
        switch (command)
        {
            case AsyncInteractionCommand interactionCommand:
                PendingAsyncCommand = interactionCommand;
                pendingAsyncAccepted = false;
                LastActionStatus = $"Sent {interactionCommand.Type} for interaction "
                    + $"{interactionCommand.InteractionSequence}; waiting for Forge.";
                break;
            case ReplyCommand reply:
                if (PendingQuery?.RequestId == reply.RequestId)
                {
                    PendingQuery = null;
                }
                LastActionStatus = $"Sent reply for query {reply.RequestId}; waiting for Forge.";
                break;
        }
    }
}
