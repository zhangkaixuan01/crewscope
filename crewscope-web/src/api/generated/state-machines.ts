/* eslint-disable */
// GENERATED FILE. Source: crewscope-domain transition maps.
// Regenerate with: node scripts/generate-state-machine.mjs
// Domain source SHA-256: 0a0b620883eee933ee457275677283a53a7b3b4539e2c8f0e2f99d268e595869

export interface GeneratedStateMachine {
  readonly name: string
  readonly statusType: string
  readonly states: readonly string[]
  readonly transitions: Readonly<Record<string, readonly string[]>>
  readonly terminalStates: readonly string[]
}

export const stateMachines = {
  "AccountOrganizationBinding": {
    "name": "AccountOrganizationBinding",
    "statusType": "AccountOrganizationBindingStatus",
    "states": [
      "ACTIVE",
      "DISABLED"
    ],
    "transitions": {
      "ACTIVE": [
        "DISABLED"
      ],
      "DISABLED": [
        "ACTIVE"
      ]
    },
    "terminalStates": []
  },
  "AgentProfile": {
    "name": "AgentProfile",
    "statusType": "AgentProfileStatus",
    "states": [
      "ACTIVE",
      "ARCHIVED",
      "DISABLED"
    ],
    "transitions": {
      "ACTIVE": [
        "DISABLED",
        "ARCHIVED"
      ],
      "DISABLED": [
        "ACTIVE",
        "ARCHIVED"
      ],
      "ARCHIVED": []
    },
    "terminalStates": [
      "ARCHIVED"
    ]
  },
  "AgentTemplateDefinition": {
    "name": "AgentTemplateDefinition",
    "statusType": "AgentTemplateStatus",
    "states": [
      "ACTIVE",
      "ARCHIVED",
      "DISABLED"
    ],
    "transitions": {
      "ACTIVE": [
        "DISABLED",
        "ARCHIVED"
      ],
      "DISABLED": [
        "ACTIVE",
        "ARCHIVED"
      ],
      "ARCHIVED": []
    },
    "terminalStates": [
      "ARCHIVED"
    ]
  },
  "LoginIdentity": {
    "name": "LoginIdentity",
    "statusType": "LoginIdentityStatus",
    "states": [
      "ACTIVE",
      "DISABLED",
      "REVOKED"
    ],
    "transitions": {
      "ACTIVE": [
        "DISABLED",
        "REVOKED"
      ],
      "DISABLED": [
        "ACTIVE",
        "REVOKED"
      ],
      "REVOKED": []
    },
    "terminalStates": [
      "REVOKED"
    ]
  },
  "ModelCatalogEntry": {
    "name": "ModelCatalogEntry",
    "statusType": "ModelRegistryStatus",
    "states": [
      "ACTIVE",
      "ARCHIVED",
      "DISABLED"
    ],
    "transitions": {
      "ACTIVE": [
        "DISABLED",
        "ARCHIVED"
      ],
      "DISABLED": [
        "ACTIVE",
        "ARCHIVED"
      ],
      "ARCHIVED": []
    },
    "terminalStates": [
      "ARCHIVED"
    ]
  },
  "ModelProviderDefinition": {
    "name": "ModelProviderDefinition",
    "statusType": "ModelRegistryStatus",
    "states": [
      "ACTIVE",
      "ARCHIVED",
      "DISABLED"
    ],
    "transitions": {
      "ACTIVE": [
        "DISABLED",
        "ARCHIVED"
      ],
      "DISABLED": [
        "ACTIVE",
        "ARCHIVED"
      ],
      "ARCHIVED": []
    },
    "terminalStates": [
      "ARCHIVED"
    ]
  },
  "Principal": {
    "name": "Principal",
    "statusType": "PrincipalStatus",
    "states": [
      "ACTIVE",
      "ARCHIVED",
      "DISABLED",
      "SUSPENDED"
    ],
    "transitions": {
      "ACTIVE": [
        "SUSPENDED",
        "DISABLED",
        "ARCHIVED"
      ],
      "SUSPENDED": [
        "ACTIVE",
        "DISABLED",
        "ARCHIVED"
      ],
      "DISABLED": [
        "ACTIVE",
        "ARCHIVED"
      ],
      "ARCHIVED": []
    },
    "terminalStates": [
      "ARCHIVED"
    ]
  },
  "RuntimeWorker": {
    "name": "RuntimeWorker",
    "statusType": "RuntimeWorkerStatus",
    "states": [
      "ACTIVE",
      "DISABLED",
      "DRAINING",
      "REGISTERED"
    ],
    "transitions": {
      "REGISTERED": [
        "ACTIVE",
        "DISABLED"
      ],
      "ACTIVE": [
        "DRAINING",
        "DISABLED"
      ],
      "DRAINING": [
        "ACTIVE",
        "DISABLED"
      ],
      "DISABLED": [
        "ACTIVE"
      ]
    },
    "terminalStates": []
  },
  "StepExecution": {
    "name": "StepExecution",
    "statusType": "StepExecutionStatus",
    "states": [
      "CANCELLED",
      "FAILED_FINAL",
      "FAILED_RETRYABLE",
      "PENDING",
      "READY",
      "RUNNING",
      "SKIPPED",
      "SUCCEEDED",
      "WAITING"
    ],
    "transitions": {
      "PENDING": [
        "READY",
        "SKIPPED",
        "CANCELLED"
      ],
      "READY": [
        "RUNNING",
        "SKIPPED",
        "CANCELLED"
      ],
      "RUNNING": [
        "WAITING",
        "SUCCEEDED",
        "FAILED_RETRYABLE",
        "FAILED_FINAL",
        "CANCELLED"
      ],
      "WAITING": [
        "READY",
        "FAILED_FINAL",
        "CANCELLED"
      ],
      "FAILED_RETRYABLE": [
        "READY",
        "FAILED_FINAL",
        "CANCELLED"
      ],
      "SUCCEEDED": [],
      "FAILED_FINAL": [],
      "SKIPPED": [],
      "CANCELLED": []
    },
    "terminalStates": [
      "CANCELLED",
      "FAILED_FINAL",
      "SKIPPED",
      "SUCCEEDED"
    ]
  },
  "Task": {
    "name": "Task",
    "statusType": "TaskStatus",
    "states": [
      "ACTIVE",
      "CANCELLED",
      "COMPLETED",
      "CREATED",
      "FAILED",
      "WAITING"
    ],
    "transitions": {
      "CREATED": [
        "ACTIVE",
        "CANCELLED"
      ],
      "ACTIVE": [
        "WAITING",
        "COMPLETED",
        "FAILED",
        "CANCELLED"
      ],
      "WAITING": [
        "ACTIVE",
        "COMPLETED",
        "FAILED",
        "CANCELLED"
      ],
      "COMPLETED": [],
      "FAILED": [],
      "CANCELLED": []
    },
    "terminalStates": [
      "CANCELLED",
      "COMPLETED",
      "FAILED"
    ]
  },
  "TaskExecution": {
    "name": "TaskExecution",
    "statusType": "TaskExecutionStatus",
    "states": [
      "CANCELLED",
      "CANCEL_REQUESTED",
      "CLAIMED",
      "COMPLETED",
      "CREATED",
      "FAILED",
      "MANUAL_TAKEOVER",
      "PAUSED",
      "PAUSE_REQUESTED",
      "PREPARING",
      "READY",
      "RECOVERING",
      "RUNNING",
      "WAITING"
    ],
    "transitions": {
      "CREATED": [
        "READY",
        "CANCEL_REQUESTED"
      ],
      "READY": [
        "CLAIMED",
        "WAITING",
        "CANCEL_REQUESTED"
      ],
      "CLAIMED": [
        "PREPARING",
        "RECOVERING",
        "CANCEL_REQUESTED"
      ],
      "PREPARING": [
        "RUNNING",
        "RECOVERING",
        "CANCEL_REQUESTED"
      ],
      "RUNNING": [
        "WAITING",
        "PAUSE_REQUESTED",
        "RECOVERING",
        "CANCEL_REQUESTED",
        "MANUAL_TAKEOVER",
        "COMPLETED",
        "FAILED"
      ],
      "WAITING": [
        "READY",
        "CANCEL_REQUESTED",
        "MANUAL_TAKEOVER"
      ],
      "PAUSE_REQUESTED": [
        "PAUSED",
        "COMPLETED",
        "CANCEL_REQUESTED"
      ],
      "PAUSED": [
        "READY",
        "CANCEL_REQUESTED"
      ],
      "RECOVERING": [
        "READY",
        "CANCEL_REQUESTED",
        "FAILED"
      ],
      "CANCEL_REQUESTED": [
        "CANCELLED"
      ],
      "MANUAL_TAKEOVER": [
        "COMPLETED",
        "FAILED",
        "CANCEL_REQUESTED"
      ],
      "COMPLETED": [],
      "FAILED": [],
      "CANCELLED": []
    },
    "terminalStates": [
      "CANCELLED",
      "COMPLETED",
      "FAILED"
    ]
  },
  "TeamInvitation": {
    "name": "TeamInvitation",
    "statusType": "TeamInvitationStatus",
    "states": [
      "ACCEPTED",
      "EXPIRED",
      "PENDING",
      "REVOKED"
    ],
    "transitions": {
      "PENDING": [
        "ACCEPTED",
        "REVOKED",
        "EXPIRED"
      ],
      "ACCEPTED": [],
      "REVOKED": [],
      "EXPIRED": []
    },
    "terminalStates": [
      "ACCEPTED",
      "EXPIRED",
      "REVOKED"
    ]
  },
  "TeamMember": {
    "name": "TeamMember",
    "statusType": "TeamMemberStatus",
    "states": [
      "ACTIVE",
      "INVITED",
      "LEFT",
      "REMOVED",
      "SUSPENDED"
    ],
    "transitions": {
      "INVITED": [
        "ACTIVE",
        "REMOVED"
      ],
      "ACTIVE": [
        "SUSPENDED",
        "LEFT",
        "REMOVED"
      ],
      "SUSPENDED": [
        "ACTIVE",
        "LEFT",
        "REMOVED"
      ],
      "LEFT": [
        "ACTIVE",
        "REMOVED"
      ],
      "REMOVED": [
        "INVITED"
      ]
    },
    "terminalStates": []
  },
  "TeamRole": {
    "name": "TeamRole",
    "statusType": "TeamRoleStatus",
    "states": [
      "ACTIVE",
      "ARCHIVED",
      "DISABLED"
    ],
    "transitions": {
      "ACTIVE": [
        "DISABLED",
        "ARCHIVED"
      ],
      "DISABLED": [
        "ACTIVE",
        "ARCHIVED"
      ],
      "ARCHIVED": []
    },
    "terminalStates": [
      "ARCHIVED"
    ]
  },
  "UserAccount": {
    "name": "UserAccount",
    "statusType": "AccountStatus",
    "states": [
      "ACTIVE",
      "ARCHIVED",
      "DISABLED",
      "LOCKED"
    ],
    "transitions": {
      "ACTIVE": [
        "LOCKED",
        "DISABLED",
        "ARCHIVED"
      ],
      "LOCKED": [
        "ACTIVE",
        "DISABLED",
        "ARCHIVED"
      ],
      "DISABLED": [
        "ACTIVE",
        "ARCHIVED"
      ],
      "ARCHIVED": []
    },
    "terminalStates": [
      "ARCHIVED"
    ]
  },
  "WorkItem": {
    "name": "WorkItem",
    "statusType": "WorkItemStatus",
    "states": [
      "ARCHIVED",
      "BACKLOG",
      "BLOCKED",
      "CANCELLED",
      "DONE",
      "IN_PROGRESS",
      "IN_REVIEW",
      "READY"
    ],
    "transitions": {
      "BACKLOG": [
        "READY",
        "CANCELLED"
      ],
      "READY": [
        "IN_PROGRESS",
        "CANCELLED"
      ],
      "IN_PROGRESS": [
        "IN_REVIEW",
        "BLOCKED",
        "CANCELLED"
      ],
      "IN_REVIEW": [
        "IN_PROGRESS",
        "BLOCKED",
        "DONE",
        "CANCELLED"
      ],
      "BLOCKED": [
        "READY",
        "IN_PROGRESS",
        "IN_REVIEW",
        "CANCELLED"
      ],
      "DONE": [
        "ARCHIVED"
      ],
      "CANCELLED": [
        "ARCHIVED"
      ],
      "ARCHIVED": []
    },
    "terminalStates": [
      "ARCHIVED"
    ]
  }
} as const satisfies Readonly<Record<string, GeneratedStateMachine>>

export type StateMachineName = keyof typeof stateMachines
export const workItemStateMachine = stateMachines.WorkItem
