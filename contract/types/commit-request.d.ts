export type OnDependents = "block" | "convertToValues";

export interface CommitRequest {
  anchor?: AnchorChoice;
  excludeErrorCells: boolean;
  onDependents: OnDependents;
  params: ParamValue[];
  planHash: string;
  regionHashes: RegionHash[];
  [k: string]: unknown;
}
export interface AnchorChoice {
  address: string;
  sheetId: string;
  [k: string]: unknown;
}
export interface ParamValue {
  name: string;
  value?: unknown;
  [k: string]: unknown;
}
export interface RegionHash {
  contentHash: string;
  entityId: string;
  [k: string]: unknown;
}
