package com.stottlerhenke.simbionic.engine.debug;

import java.util.ArrayList;

import com.stottlerhenke.simbionic.api.SB_Exception;
import com.stottlerhenke.simbionic.common.Enum;
import com.stottlerhenke.simbionic.engine.core.SB_ExecutionFrame;
import com.stottlerhenke.simbionic.common.SB_Logger;
import com.stottlerhenke.simbionic.common.SB_ID;
import com.stottlerhenke.simbionic.common.debug.DMFieldMap;
import com.stottlerhenke.simbionic.common.debug.SB_DebugMessage;
/**
 * This class determines the appropriate action to be taken by the debugger
 * for each execution event generated by the ENGINE.  It is in
 * charge of controlling step mode, including waiting for and processing step
 * messages from the client. 
 */

public class SB_EventHandler
{
  public SB_EventHandler(SB_EngineQueryInterface queryHandler, SB_Debugger debugger, SB_Logger logger)
  {
    _msgHandler = null;
    _queryHandler = queryHandler;
    _runMode = ERunMode.kMODE_RUN;
    _stepMode = EStepMode.kSTEP_PAUSE;
    _targetEntity = SB_ID.NULL_ENTITY;
    _targetFrame = SB_ExecutionFrame.NULL_FRAME;
    _debugger = debugger;
    _logger = logger;

    _awaitedEvents = new ArrayList();
  }


  /**
   * Saves a pointer to the message handler (needed to wait for messages).
   * @param msgHandler the message handler
   */
  public void SetMessageHandler(SB_MessageHandler msgHandler)
  {
    _msgHandler = msgHandler;
  }

  /**
   * Chooses the appropriate response to the given execution event and then performs that action.
   * @param event the execution event to respond to
   * @return true if a gui_shutdown message was received while waiting, false otherwise
   */
  public boolean ProcessEvent(SB_DebugEvent event) throws SB_Exception
  {
    if (event.GetType() == EEventType.kEVT_BREAKPOINT_HIT)
    {
      // hitting a breakpoint is equivalent to hitting 'pause' in the client
      _runMode = ERunMode.kMODE_STEP;
      _stepMode = EStepMode.kSTEP_PAUSE;

      return WaitForNextStep(_logger);
    }
    else if (_runMode.getState() == ERunMode.kMODE_STEP.getState())
    {
      // have we hit the end of a step?
      if (IsStepCompleted(event))
      {
        // notify the client that the step is finished
        long entityId = event.GetIdField("entity");
        boolean shouldQuery = (_stepMode==EStepMode.kSTEP_OVER_ACTIVE) ||
                          (_stepMode==EStepMode.kSTEP_ONE_TICK) ||
                          (_stepMode==EStepMode.kSTEP_RUN_TO_FINAL);

        DMFieldMap fields = new DMFieldMap();
        fields.ADD_ID_FIELD( "entity", entityId );
        fields.ADD_INT_FIELD( "frame", _queryHandler.GetEntityCurrentFrame(entityId));
        fields.ADD_LONG_FIELD( "alive", _queryHandler.GetEntityTimeAlive( entityId ));
        fields.ADD_INT_FIELD(  "query", shouldQuery );
        _msgHandler.NotifyClient(new SB_DebugEvent(EEventType.kEVT_STEP_FINISHED, fields));

        return WaitForNextStep(_logger);
      }
      else
      {
        UpdateAwaitedEventsList(event);
      }
    }

    return false;
  }

  /**
   * Processes step messages from the debug client.
   * @param msg the step message to process
   */
  public void HandleStepMessage(SB_DebugMessage msg) throws SB_Exception
  {
    _targetEntity = SB_ID.NULL_ENTITY;
    _targetFrame = SB_ExecutionFrame.NULL_FRAME;

    switch (msg.GetMsgType())
    {
    case SB_DebugMessage.kDBG_GUI_START: _runMode = ERunMode.kMODE_RUN;
                                             break;

    case SB_DebugMessage.kDBG_GUI_STEP:	 _runMode = ERunMode.kMODE_STEP;
                                             _stepMode = EStepMode.kSTEP_OVER;
                                             break;

    case SB_DebugMessage.kDBG_GUI_STEP_INTO: _runMode = ERunMode.kMODE_STEP;
                                                     _stepMode = EStepMode.kSTEP_INTO;
                                                     break;

    case SB_DebugMessage.kDBG_GUI_STEP_ONE_TICK: _runMode = ERunMode.kMODE_STEP;
                                                             _stepMode = EStepMode.kSTEP_ONE_TICK;
                                                             _targetEntity = msg.GetIdField("stepEntity");
                                                             break;

    case SB_DebugMessage.kDBG_GUI_RUN_TO_FINAL: _runMode = ERunMode.kMODE_STEP;
                                                            _stepMode = EStepMode.kSTEP_RUN_TO_FINAL;
                                                            _targetEntity = msg.GetIdField("stepEntity");
                                                            _targetFrame = msg.GetIntField("frame");
                                                            break;

    default: throw new SB_Exception("SB_EventHandler received a non-step message " +  msg.GetTypeName());
    }


    BuildAwaitedEventsList(msg);
  }

  /**
   * Puts the debugger in STEP mode.
   * @param stepMsg the pause message from the client
   */
  public void EnterStepMode(SB_DebugMessage stepMsg) throws SB_Exception
  {
    _runMode = ERunMode.kMODE_STEP;
    _stepMode = EStepMode.kSTEP_PAUSE;
    _targetEntity = SB_ID.NULL_ENTITY;
    _targetFrame = SB_ExecutionFrame.NULL_FRAME;

 // RTH don't send as there may not be a current entity when Pause is pressed
    // pretend that a step has just finished and client should now wait for a Step button-press
//    long entityId = _queryHandler.GetCurrentEntity();
//    DMFieldMap fields = new DMFieldMap();
//    fields.ADD_ID_FIELD( "entity", entityId );
//    fields.ADD_INT_FIELD( "frame", _queryHandler.GetEntityCurrentFrame(entityId));
//    fields.ADD_LONG_FIELD( "alive", _queryHandler.GetEntityTimeAlive( entityId ));
//    fields.ADD_INT_FIELD( "query", 1 ); //JRL
//    _msgHandler.NotifyClient(new SB_DebugEvent(EEventType.kEVT_STEP_FINISHED, fields));

    WaitForNextStep(_logger);
  }

  /**
   * Retrieves status of the Event Handler's current step mode.
   * @param entity [returned] the target entity, or NULL_ENTITY if none
   * @param frame [returned] the target frame, or NULL_FRAME if none
   * @return the current STEP mode type, or kNO_STEP if in RUN mode
   */
  public StepModeInfo GetStepModeInfo()
  {
    long entity = _targetEntity;
    int frame = _targetFrame;
    int step = (_runMode == ERunMode.kMODE_STEP) ? _stepMode : EStepMode.kNO_STEP;

    return new StepModeInfo(step, entity, frame);
  }

  /**
   * Determine if the given event satisfies the completion conditions for the last
   * step command from the client.
   * @param event the event to evaluate
   * @return true if the completion conditions are satisfied, false otherwise
   */
  boolean IsStepCompleted(SB_DebugEvent event)
  {
    // check the event and current state against the set of awaited events
    int nCount = _awaitedEvents.size();
    for( int x = 0; x < nCount; x++ )
    {
      EventPattern pattern = (EventPattern) _awaitedEvents.get(x);
      if (event.GetType() == pattern._eventType)
      {
        long currEntityId = event.GetIdField("entity");

        if ((pattern._entityId == SB_ID.NULL_ENTITY) || (currEntityId == pattern._entityId))
        {
          long currFrameId = event.IsField("frame") ? event.GetIntField("frame") : _queryHandler.GetCurrentEntity();

          if ((pattern._frameId == SB_ExecutionFrame.NULL_FRAME) || (currFrameId == pattern._frameId))
          {
            // an awaited event signalling the completion of a step has occurred
            _awaitedEvents = new ArrayList();
            return true;
          }
        }
      }
    }

   return false;
  }

  /**
   * Waits for and then processes the next step message from the client.
   * @return true if a gui_shutdown message was received while waiting, false otherwise
   */
  boolean WaitForNextStep(SB_Logger logger) throws SB_Exception
  {
    // wait for another step message from the client
    // (but process other messages in the meantime)
    SB_DebugMessage stepMsg = _msgHandler.WaitForMessage(SB_DebugMessage.kDBG_GUI_START,SB_DebugMessage.kDBG_GUI_RUN_TO_FINAL,true, _debugger);

    if (stepMsg == null)
    {
      logger.log(" [[ shutdown: abandoning wait-loop in debug event handler ]]",SB_Logger.DEBUGGER);
      return true;
    }

    HandleStepMessage(stepMsg);

    return false;
  }

  /**
   * Determines the set of events that could complete the current step.
   * @param stepMsg the step message from the client
   */
  void BuildAwaitedEventsList(SB_DebugMessage stepMsg) throws SB_Exception
  {
    _awaitedEvents = new ArrayList();

    switch (stepMsg.GetMsgType())
    {
    case SB_DebugMessage.kDBG_GUI_PAUSE:
            // enter step mode
            break;

    case SB_DebugMessage.kDBG_GUI_START:
            // end step mode (hence no awaited events)
            break;

    case SB_DebugMessage.kDBG_GUI_STEP:
            // step along, but skip over behavior invocations
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_NODE_CHANGED, SB_ID.NULL_ENTITY, SB_ExecutionFrame.NULL_FRAME ));
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_CONDITION_CHECKED, SB_ID.NULL_ENTITY, SB_ExecutionFrame.NULL_FRAME ));
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_CONDITION_FOLLOWED, SB_ID.NULL_ENTITY, SB_ExecutionFrame.NULL_FRAME ));
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_VAR_CHANGED, SB_ID.NULL_ENTITY, SB_ExecutionFrame.NULL_FRAME ));
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_GLOBAL_CHANGED, SB_ID.NULL_ENTITY, SB_ExecutionFrame.NULL_FRAME ));
            break;

    case SB_DebugMessage.kDBG_GUI_STEP_INTO:
            // step along, stepping into behaviors
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_NODE_CHANGED, SB_ID.NULL_ENTITY, SB_ExecutionFrame.NULL_FRAME ));
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_CONDITION_CHECKED, SB_ID.NULL_ENTITY, SB_ExecutionFrame.NULL_FRAME ));
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_CONDITION_FOLLOWED, SB_ID.NULL_ENTITY, SB_ExecutionFrame.NULL_FRAME ));
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_VAR_CHANGED, SB_ID.NULL_ENTITY, SB_ExecutionFrame.NULL_FRAME ));
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_GLOBAL_CHANGED, SB_ID.NULL_ENTITY, SB_ExecutionFrame.NULL_FRAME ));
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_FRAME_COMPLETED, SB_ID.NULL_ENTITY, SB_ExecutionFrame.NULL_FRAME ));
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_FRAME_DISCARDED, SB_ID.NULL_ENTITY, SB_ExecutionFrame.NULL_FRAME ));
            break;

    case SB_DebugMessage.kDBG_GUI_STEP_ONE_TICK:
            {
            // skip to the start of the next tick for the specified entity
            long entity = stepMsg.GetIdField("stepEntity");
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_ENTITY_STARTING, entity, SB_ExecutionFrame.NULL_FRAME ));
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_ENTITY_DESTROYED, entity, SB_ExecutionFrame.NULL_FRAME ));
            }
            break;

    case SB_DebugMessage.kDBG_GUI_RUN_TO_FINAL:
            {
            // skip to the execution of a final node in the specified frame (for specified entity),
            // or until that frame is discarded due to a transition lower in the stack
            long entity = stepMsg.GetIdField("stepEntity");
            long frame = stepMsg.GetIntField("frame");

            _awaitedEvents.add( new EventPattern( EEventType.kEVT_FRAME_COMPLETED, entity, frame ));
            _awaitedEvents.add( new EventPattern( EEventType.kEVT_FRAME_DISCARDED, entity, frame ));
            }
            break;

    default: throw new SB_Exception("SB_EventHandler received a non-step message " +  stepMsg.GetTypeName());
    }
  }

  /**
   * Updates the set of events that could complete the current step.
   * @param event the event to evaluate
   */
  void UpdateAwaitedEventsList(SB_DebugEvent event)
  {
    if( _stepMode == EStepMode.kSTEP_OVER )
    {
      if (event.GetType() == EEventType.kEVT_FRAME_CREATED)
      {
        _targetEntity = event.GetIdField("entity");
        _targetFrame = event.GetIntField("parent");

        // if the new frame is on the bottom of the stack, it can't be
        // stepped over (too confusing in the editor)
        if (_targetFrame == SB_ExecutionFrame.NULL_FRAME)
                return;

        // still in STEP_OVER mode, but now actively stepping
        // over a behavior invocation
        _stepMode = EStepMode.kSTEP_OVER_ACTIVE;

        _awaitedEvents = new ArrayList();

        // a new behavior has been invoked, and we want to skip over all
        // subsequent events until either the parent frame becomes current
        // again or is discarded
        _awaitedEvents.add( new EventPattern( EEventType.kEVT_FRAME_CURRENT, _targetEntity, _targetFrame ));
        _awaitedEvents.add( new EventPattern( EEventType.kEVT_FRAME_DISCARDED, _targetEntity, _targetFrame ));
        _awaitedEvents.add( new EventPattern( EEventType.kEVT_ENTITY_DESTROYED, _targetEntity, SB_ExecutionFrame.NULL_FRAME ));
      }
    }
  }

  SB_MessageHandler	  _msgHandler;
  SB_EngineQueryInterface _queryHandler;
  SB_Debugger             _debugger;
  SB_Logger               _logger;

  ERunMode  _runMode;
  int  _stepMode;

  long  _targetEntity;
  int  _targetFrame;

  ArrayList _awaitedEvents; //EventPattern
}


class ERunMode extends Enum
{
  private ERunMode(int event){ super(event); }

  public static final ERunMode kMODE_RUN = new ERunMode(0);
  public static final ERunMode kMODE_STOP = new ERunMode(1);
  public static final ERunMode kMODE_STEP = new ERunMode(2);
}

/**
 * Describes a single event-state combination, generally
 * one that the Event Handler is waiting for.
 */
class EventPattern
{
  public EventPattern(int eventType, long entityId, long frameId)
  {
    _eventType = eventType;
    _entityId = entityId;
    _frameId = frameId;
  }

  public int _eventType;
  public long _entityId;
  public long _frameId;
}