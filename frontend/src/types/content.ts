/*
 * Content is an abstraction for storing the state of process data being viewed and edited. It is designed to break the
 * process tree-structure into doubly-linked parent/child nodes with unique (transient) keys for the purpose of
 * flattening the structure stored in Redux.
 */

export enum ContentType {
  PROCESS = 'process',
  ACTION_LIST = 'actions',
  ACTION = 'action',
  FUNCTION = 'function',
  EXPRESSION_LIST = 'expressions',
  VALUE = 'value',
  VARIABLE = 'variable',
}

export type ContentStore = {
  count: number;
  data: { [key: string]: Content };
  rootConfigKey?: string;
};

export type Content = {
  key: string;
  type: ContentType;
  parentKey?: string;
};

export type NodeContent = {
  childKeys: { [key: string]: string };
} & Content;

export type ListContent = {
  childKeys: string[];
} & Content;

export type ProcessContent = ListContent & {
  type: ContentType.PROCESS;
  name: string;
};

export type ActionListContent = ListContent & {
  type: ContentType.ACTION_LIST;
};

export type ActionContent = Content & {
  type: ContentType.ACTION;
  name: string;
  actionChildKeys: { [key: string]: string };
  inputChildKeys: { [key: string]: string };
};

export type FunctionContent = NodeContent & {
  type: ContentType.FUNCTION;
  name: string;
};

export type InputsContent = ListContent & {
  type: ContentType.EXPRESSION_LIST;
  name: string;
};

export type ValueContent = Content & {
  type: ContentType.VALUE;
  value: string;
};

export type VariableContent = Content & {
  type: ContentType.VARIABLE;
  index: number;
  path: string[];
};
