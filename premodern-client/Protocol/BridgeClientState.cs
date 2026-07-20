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
                break;
            case InteractionMessage interaction:
                CurrentInteraction = interaction;
                break;
            case QueryMessage query:
                PendingQuery = query;
                LastNotice = $"Observation-only client has pending query {query.RequestId} ({query.Kind}).";
                break;
            case ErrorMessage error:
                LastError = $"{error.Code}: {error.Message}";
                if (error.RequestId != null && PendingQuery?.RequestId == error.RequestId)
                {
                    PendingQuery = null;
                }
                break;
            case UnknownBridgeMessage unknown:
                LastNotice = $"Unknown protocol message type: {unknown.UnknownType}";
                break;
            case ProtocolProblemMessage problem:
                LastError = $"{problem.Code}: {problem.Detail}";
                break;
        }
    }
}
