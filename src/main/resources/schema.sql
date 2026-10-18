-- Saved chats and RFQs, run at every startup (spring.sql.init.mode=always), so only additive changes here.

-- One web chat. visitor_id is the private browser token (HttpOnly cookie) that links a customer's chats.
CREATE TABLE IF NOT EXISTS conversation (
    id          VARCHAR(36) PRIMARY KEY,
    visitor_id  VARCHAR(64) NOT NULL,
    started_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS conversation_visitor ON conversation (visitor_id, updated_at);

-- One customer message and the agent's answer. The embedding (float32, little-endian) is filled in
-- afterwards for the sales-team search; embedding_model says which model made it.
CREATE TABLE IF NOT EXISTS turn (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id  VARCHAR(36) NOT NULL REFERENCES conversation (id),
    question         CLOB NOT NULL,
    answer           CLOB NOT NULL,
    tools            VARCHAR(1000) NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    embedding        VARBINARY(65536),
    embedding_model  VARCHAR(100)
);
CREATE INDEX IF NOT EXISTS turn_conversation ON turn (conversation_id, id);

-- Every create_rfq result that got an id: SocksPal's RFQ number, or the local draft id.
-- request is the tool arguments as JSON (what the customer confirmed).
CREATE TABLE IF NOT EXISTS rfq (
    id               VARCHAR(64) PRIMARY KEY,
    conversation_id  VARCHAR(36) NOT NULL REFERENCES conversation (id),
    status           VARCHAR(64) NOT NULL,
    request          CLOB NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS rfq_conversation ON rfq (conversation_id);

-- Short name shown in the customer's chat list (written by the model after the first answer).
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS title VARCHAR(200);
