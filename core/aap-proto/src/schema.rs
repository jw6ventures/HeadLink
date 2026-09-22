//! Wire-level schema checks from the generated field table (`generated/schema.rs`).
//! For a message and its nested messages this verifies that every proto2 `required`
//! field is present and that known fields use a wire type their declared type allows.
//! Unknown fields are skipped, as protobuf requires; groups are rejected.
use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Varint,
    Fixed64,
    Fixed32,
    Bytes,
    Message(&'static str),
}

#[derive(Debug)]
pub struct Field {
    pub number: u32,
    pub name: &'static str,
    pub required: bool,
    pub repeated: bool,
    pub kind: Kind,
}

#[derive(Debug)]
pub struct MessageSchema {
    pub name: &'static str,
    pub fields: &'static [Field],
}

include!("generated/schema.rs");

/// Deepest message nesting validated.
pub const DEPTH_LIMIT: usize = 32;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ValidationError {
    /// Dotted field path from the top-level message, with repeated indices.
    pub path: String,
    pub reason: String,
}

impl fmt::Display for ValidationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.path, self.reason)
    }
}

impl std::error::Error for ValidationError {}

pub fn schema(name: &str) -> Option<&'static MessageSchema> {
    MESSAGES
        .binary_search_by(|m| m.name.cmp(name))
        .ok()
        .map(|i| &MESSAGES[i])
}

pub fn validate(name: &str, bytes: &[u8]) -> Result<(), ValidationError> {
    let schema = schema(name).ok_or_else(|| ValidationError {
        path: name.to_owned(),
        reason: "no generated schema".into(),
    })?;
    let short = name.rsplit('.').next().unwrap_or(name);
    walk(schema, bytes, short, 0)
}

fn error(path: &str, reason: impl Into<String>) -> ValidationError {
    ValidationError {
        path: path.to_owned(),
        reason: reason.into(),
    }
}

fn varint(bytes: &[u8], at: &mut usize, path: &str) -> Result<u64, ValidationError> {
    let mut value = 0u64;
    for shift in 0..10 {
        let byte = *bytes
            .get(*at)
            .ok_or_else(|| error(path, "truncated varint"))?;
        *at += 1;
        if shift == 9 && byte > 1 {
            return Err(error(path, "varint overflows 64 bits"));
        }
        value |= u64::from(byte & 0x7f) << (7 * shift);
        if byte & 0x80 == 0 {
            return Ok(value);
        }
    }
    Err(error(path, "varint longer than 10 bytes"))
}

fn walk(
    schema: &'static MessageSchema,
    bytes: &[u8],
    path: &str,
    depth: usize,
) -> Result<(), ValidationError> {
    if depth > DEPTH_LIMIT {
        return Err(error(path, format!("nesting deeper than {DEPTH_LIMIT}")));
    }
    let mut seen = vec![false; schema.fields.len()];
    let mut counts = vec![0usize; schema.fields.len()];
    let mut at = 0;
    while at < bytes.len() {
        let key = varint(bytes, &mut at, path)?;
        let number = key >> 3;
        let wire = (key & 7) as u8;
        if number == 0 || number > (1 << 29) - 1 {
            return Err(error(path, format!("invalid field number {number}")));
        }
        let field_index = schema
            .fields
            .binary_search_by_key(&(number as u32), |f| f.number)
            .ok();
        let field_path = |f: &Field, index: usize| {
            if f.repeated {
                format!("{path}.{}[{index}]", f.name)
            } else {
                format!("{path}.{}", f.name)
            }
        };
        let payload = match wire {
            0 => {
                varint(bytes, &mut at, path)?;
                None
            }
            1 | 5 => {
                let width = if wire == 1 { 8 } else { 4 };
                if bytes.len() - at < width {
                    return Err(error(path, format!("truncated fixed field {number}")));
                }
                at += width;
                None
            }
            2 => {
                let length = varint(bytes, &mut at, path)?;
                if length > (bytes.len() - at) as u64 {
                    return Err(error(
                        path,
                        format!("field {number} length exceeds message"),
                    ));
                }
                let start = at;
                at += length as usize;
                Some(&bytes[start..at])
            }
            3 | 4 => return Err(error(path, format!("group wire type in field {number}"))),
            _ => {
                return Err(error(
                    path,
                    format!("invalid wire type {wire} in field {number}"),
                ))
            }
        };
        let Some(index) = field_index else { continue };
        let field = &schema.fields[index];
        let this = field_path(field, counts[index]);
        let allowed = match field.kind {
            Kind::Varint => wire == 0 || (field.repeated && wire == 2),
            Kind::Fixed64 => wire == 1 || (field.repeated && wire == 2),
            Kind::Fixed32 => wire == 5 || (field.repeated && wire == 2),
            Kind::Bytes | Kind::Message(_) => wire == 2,
        };
        if !allowed {
            return Err(error(
                &this,
                format!("wire type {wire} does not match declared type"),
            ));
        }
        if let (Kind::Message(target), Some(payload)) = (field.kind, payload) {
            let nested = self::schema(target)
                .ok_or_else(|| error(&this, format!("no generated schema for {target}")))?;
            walk(nested, payload, &this, depth + 1)?;
        }
        seen[index] = true;
        counts[index] += 1;
    }
    for (field, seen) in schema.fields.iter().zip(seen) {
        if field.required && !seen {
            return Err(error(
                &format!("{path}.{}", field.name),
                "required field missing",
            ));
        }
    }
    Ok(())
}
