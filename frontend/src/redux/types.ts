import { FrameEditorState } from './slices';

export const FRAME_EDITOR_REDUX_NAMESPACE = 'LOGICFORGE_FRAME_EDITOR';

export type LogicForgeReduxState = {
  [FRAME_EDITOR_REDUX_NAMESPACE]: FrameEditorState;
};
