'use strict';

const DECISION_SCHEMA = require('../schemas/decision.schema.json');

function cloneJson(value) {
  return JSON.parse(JSON.stringify(value));
}

function actionPropertiesFrom(schema) {
  return schema.properties.action.properties;
}

const ACTION_NAMES = Object.freeze([
  ...actionPropertiesFrom(DECISION_SCHEMA).name.enum
]);

const TARGET_SCOPE_VALUES = Object.freeze([
  ...actionPropertiesFrom(DECISION_SCHEMA).targetScope.enum
]);

const ACTION_REQUIRED_FIELDS = Object.freeze([
  ...DECISION_SCHEMA.properties.action.required
]);

function buildDecisionSchema({ includeDialect = false } = {}) {
  const schema = cloneJson(DECISION_SCHEMA);
  if (!includeDialect) delete schema.$schema;
  return schema;
}

module.exports = {
  ACTION_NAMES,
  ACTION_REQUIRED_FIELDS,
  TARGET_SCOPE_VALUES,
  DECISION_SCHEMA,
  buildDecisionSchema
};
